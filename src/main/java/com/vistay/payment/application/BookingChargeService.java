package com.vistay.payment.application;

import com.vistay.payment.domain.*;
import jakarta.persistence.OptimisticLockException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class BookingChargeService {

    private final BookingChargeRepository chargeRepository;
    private final BookingChargeAllocationRepository allocationRepository;
    private final BookingChargeAdjustmentRepository adjustmentRepository;
    private final FinancePaymentInstructionClient financeClient;
    private final FinanceAllocationClient financeAllocationClient;
    private final BookingBranchResolver bookingBranchResolver;
    private final TransactionTemplate writeTxTemplate;
    private final TransactionTemplate readTxTemplate;

    public BookingChargeService(BookingChargeRepository chargeRepository,
                                BookingChargeAllocationRepository allocationRepository,
                                BookingChargeAdjustmentRepository adjustmentRepository,
                                @Autowired(required = false) FinancePaymentInstructionClient financeClient,
                                @Autowired(required = false) FinanceAllocationClient financeAllocationClient) {
        this(chargeRepository, allocationRepository, adjustmentRepository, financeClient, financeAllocationClient, null, null);
    }

    public BookingChargeService(BookingChargeRepository chargeRepository,
                                BookingChargeAllocationRepository allocationRepository,
                                BookingChargeAdjustmentRepository adjustmentRepository,
                                @Autowired(required = false) FinancePaymentInstructionClient financeClient,
                                @Autowired(required = false) FinanceAllocationClient financeAllocationClient,
                                @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this(chargeRepository, allocationRepository, adjustmentRepository, financeClient, financeAllocationClient, transactionManager, null);
    }

    @Autowired
    public BookingChargeService(BookingChargeRepository chargeRepository,
                                BookingChargeAllocationRepository allocationRepository,
                                BookingChargeAdjustmentRepository adjustmentRepository,
                                @Autowired(required = false) FinancePaymentInstructionClient financeClient,
                                @Autowired(required = false) FinanceAllocationClient financeAllocationClient,
                                @Autowired(required = false) PlatformTransactionManager transactionManager,
                                @Autowired(required = false) BookingBranchResolver bookingBranchResolver) {
        this.chargeRepository = chargeRepository;
        this.allocationRepository = allocationRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.financeClient = financeClient;
        this.financeAllocationClient = financeAllocationClient;
        this.bookingBranchResolver = bookingBranchResolver;

        if (transactionManager != null) {
            this.writeTxTemplate = new TransactionTemplate(transactionManager);
            this.writeTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            this.readTxTemplate = new TransactionTemplate(transactionManager);
            this.readTxTemplate.setReadOnly(true);
            this.readTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            // Passthrough template for unit tests without full transaction manager
            TransactionTemplate passthrough = new TransactionTemplate() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    return action.doInTransaction(new SimpleTransactionStatus());
                }
            };
            this.writeTxTemplate = passthrough;
            this.readTxTemplate = passthrough;
        }
    }

    public BookingChargeResponse createCharge(CreateBookingChargeCommand cmd, UUID actorId) {
        if (actorId == null) {
            throw new SecurityException("Authenticated actor ID is required to create a booking charge");
        }

        // 1. Idempotency pre-check
        Optional<BookingCharge> existing = readTxTemplate.execute(status ->
                chargeRepository.findByIdempotencyKey(cmd.idempotencyKey()));
        if (existing != null && existing.isPresent()) {
            BookingCharge existingCharge = existing.get();
            verifyCreatePayloadMatches(existingCharge, cmd);
            return BookingChargeResponse.from(existingCharge);
        }

        Boolean codeExists = readTxTemplate.execute(status ->
                chargeRepository.existsByChargeCode(cmd.chargeCode()));
        if (Boolean.TRUE.equals(codeExists)) {
            throw new IllegalArgumentException("Charge code already exists: " + cmd.chargeCode());
        }

        Boolean finReqExists = readTxTemplate.execute(status ->
                chargeRepository.existsByFinancePaymentRequestId(cmd.financePaymentRequestId()));
        if (Boolean.TRUE.equals(finReqExists)) {
            throw new IllegalArgumentException("Booking charge already exists for Finance Payment Request: " + cmd.financePaymentRequestId());
        }

        // 2. Strict Finance SSOT Validation (Fail-closed)
        if (financeClient == null) {
            throw new IllegalStateException("Finance client is unavailable for SSOT verification");
        }

        FinancePaymentInstruction instruction = financeClient.get(cmd.financePaymentRequestId());
        if (instruction == null) {
            throw new IllegalArgumentException("Finance payment instruction not found: " + cmd.financePaymentRequestId());
        }
        if (instruction.expiresAt() != null && instruction.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Finance payment instruction is expired");
        }
        if (instruction.amount() != null && cmd.originalAmount().compareTo(instruction.amount()) != 0) {
            throw new IllegalArgumentException("Charge amount (" + cmd.originalAmount() + ") does not match Finance instruction amount (" + instruction.amount() + ")");
        }
        if (instruction.bookingId() != null && !cmd.bookingId().equals(instruction.bookingId())) {
            throw new IllegalArgumentException("Booking ID does not match Finance instruction booking ID");
        }
        if (instruction.customerId() != null && !cmd.customerId().equals(instruction.customerId())) {
            throw new IllegalArgumentException("Customer ID does not match Finance instruction customer ID");
        }
        if (instruction.currency() != null && cmd.currency() != null && !cmd.currency().equalsIgnoreCase(instruction.currency())) {
            throw new IllegalArgumentException("Currency does not match Finance instruction currency");
        }

        String effectiveCurrency = (instruction.currency() != null && !instruction.currency().isBlank())
                ? instruction.currency()
                : cmd.currency();

        // 3. Authoritative Branch Resolution and Verification
        UUID effectiveBranchId = cmd.branchId();
        if (bookingBranchResolver != null) {
            Optional<UUID> authoritativeBranch = bookingBranchResolver.resolveBranchId(cmd.bookingId());
            if (authoritativeBranch.isPresent()) {
                UUID verifiedBranchId = authoritativeBranch.get();
                if (cmd.branchId() != null && !cmd.branchId().equals(verifiedBranchId)) {
                    throw new IllegalArgumentException("Requested branch ID (" + cmd.branchId()
                            + ") conflicts with authoritative booking branch (" + verifiedBranchId + ")");
                }
                effectiveBranchId = verifiedBranchId;
            }
        }

        BookingCharge charge = new BookingCharge(
                cmd.chargeCode(),
                cmd.financePaymentRequestId(),
                cmd.bookingId(),
                effectiveBranchId,
                cmd.customerId(),
                cmd.chargeType(),
                cmd.originalAmount(),
                effectiveCurrency,
                cmd.dueDate(),
                cmd.description(),
                actorId,
                cmd.idempotencyKey()
        );

        try {
            return writeTxTemplate.execute(status ->
                    BookingChargeResponse.from(chargeRepository.saveAndFlush(charge)));
        } catch (DataIntegrityViolationException ex) {
            // Concurrent race condition handling:
            // Write transaction has rolled back and closed. Query winner in clean read transaction.
            Optional<BookingCharge> raceExisting = readTxTemplate.execute(status ->
                    chargeRepository.findByIdempotencyKey(cmd.idempotencyKey()));
            if (raceExisting != null && raceExisting.isPresent()) {
                BookingCharge winner = raceExisting.get();
                verifyCreatePayloadMatches(winner, cmd);
                return BookingChargeResponse.from(winner);
            }
            throw ex;
        }
    }

    public BookingChargeResponse applyPayment(UUID chargeId, ApplyPaymentToChargeCommand cmd, UUID actorId) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 1. Anti-replay and deduplication pre-check
            Optional<BookingChargeAllocation> existingAlloc = readTxTemplate.execute(status ->
                    allocationRepository.findByFinanceAllocationId(cmd.financeAllocationId()));
            if (existingAlloc != null && existingAlloc.isPresent()) {
                BookingChargeAllocation alloc = existingAlloc.get();
                if (alloc.getChargeId().equals(chargeId)
                        && alloc.getPaymentTransactionId().equals(cmd.paymentTransactionId())
                        && alloc.getAllocatedAmount().compareTo(cmd.paymentAmount()) == 0) {
                    // Idempotent replay: exact same allocation re-sent
                    return BookingChargeResponse.from(findChargeOrThrow(chargeId));
                }
                throw new IllegalStateException("Duplicate finance allocation ID already recorded: " + cmd.financeAllocationId());
            }

            BookingCharge charge = findChargeOrThrow(chargeId);

            // 2. Strict Finance Allocation SSOT verification (Fail-closed)
            if (financeAllocationClient == null) {
                throw new IllegalStateException("Finance allocation client is required for SSOT verification: fail-closed");
            }

            FinanceAllocationRecord record = financeAllocationClient.getAllocation(cmd.financeAllocationId());
            if (record == null) {
                throw new IllegalArgumentException("Finance allocation record not found: " + cmd.financeAllocationId());
            }
            if (!record.financePaymentRequestId().equals(charge.getFinancePaymentRequestId())) {
                throw new IllegalArgumentException("Finance allocation does not match collection order payment request");
            }
            if (!record.paymentTransactionId().equals(cmd.paymentTransactionId())) {
                throw new IllegalArgumentException("Payment transaction ID mismatch with verified Finance allocation");
            }
            if (cmd.paymentAmount().compareTo(record.allocatedAmount()) != 0) {
                throw new IllegalArgumentException("Payment amount (" + cmd.paymentAmount() + ") does not match Finance allocation amount (" + record.allocatedAmount() + ")");
            }
            if (!charge.getCurrency().equalsIgnoreCase(record.currency())) {
                throw new IllegalArgumentException("Currency mismatch with verified Finance allocation");
            }
            if (!"CONFIRMED".equalsIgnoreCase(record.status())) {
                throw new IllegalStateException("Finance allocation is not in CONFIRMED status: " + record.status());
            }

            try {
                return writeTxTemplate.execute(status -> {
                    // 3. Read fresh charge inside write transaction boundary to avoid lost update
                    BookingCharge txCharge = findChargeOrThrow(chargeId);
                    txCharge.applyPayment(cmd.paymentAmount(), cmd.paymentTransactionId());

                    BookingChargeAllocation allocation = new BookingChargeAllocation(
                            txCharge.getId(),
                            cmd.financeAllocationId(),
                            cmd.paymentTransactionId(),
                            cmd.paymentAmount(),
                            txCharge.getCurrency(),
                            actorId,
                            cmd.note()
                    );

                    allocationRepository.saveAndFlush(allocation);
                    return BookingChargeResponse.from(chargeRepository.saveAndFlush(txCharge));
                });
            } catch (DataIntegrityViolationException ex) {
                // Concurrent race condition handling on finance_allocation_id
                Optional<BookingChargeAllocation> raceAlloc = readTxTemplate.execute(status ->
                        allocationRepository.findByFinanceAllocationId(cmd.financeAllocationId()));
                if (raceAlloc != null && raceAlloc.isPresent()) {
                    BookingChargeAllocation alloc = raceAlloc.get();
                    if (alloc.getChargeId().equals(chargeId)
                            && alloc.getPaymentTransactionId().equals(cmd.paymentTransactionId())
                            && alloc.getAllocatedAmount().compareTo(cmd.paymentAmount()) == 0) {
                        return BookingChargeResponse.from(findChargeOrThrow(chargeId));
                    }
                    throw new IllegalStateException("Duplicate finance allocation ID under race condition: " + cmd.financeAllocationId());
                }
                throw ex;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException ex) {
                if (attempt == maxRetries) {
                    throw ex;
                }
                // Refresh entity in clean transaction and retry
            }
        }
        throw new IllegalStateException("Failed to apply payment after max retries due to concurrent optimistic locking");
    }

    public BookingChargeResponse waiveCharge(UUID chargeId, WaiveBookingChargeCommand cmd, UUID actorId) {
        if (actorId == null) {
            throw new SecurityException("Authenticated approver admin user ID is required");
        }
        return writeTxTemplate.execute(status -> {
            BookingCharge charge = findChargeOrThrow(chargeId);
            charge.waive(cmd.waiveAmount(), actorId, cmd.reason());

            BookingChargeAdjustment adjustment = new BookingChargeAdjustment(
                    charge.getId(),
                    "WAIVER",
                    cmd.waiveAmount(),
                    cmd.reason(),
                    actorId
            );
            adjustmentRepository.save(adjustment);

            return BookingChargeResponse.from(chargeRepository.saveAndFlush(charge));
        });
    }

    public BookingChargeResponse cancelCharge(UUID chargeId, CancelBookingChargeCommand cmd, UUID actorId) {
        if (actorId == null) {
            throw new SecurityException("Authenticated actor ID is required to cancel charge");
        }
        return writeTxTemplate.execute(status -> {
            BookingCharge charge = findChargeOrThrow(chargeId);
            BigDecimal voidAmount = charge.getOutstandingBalance();
            charge.cancel(actorId, cmd.reason());

            if (voidAmount.compareTo(BigDecimal.ZERO) > 0) {
                BookingChargeAdjustment adjustment = new BookingChargeAdjustment(
                        charge.getId(),
                        "CANCELLATION",
                        voidAmount,
                        cmd.reason(),
                        actorId
                );
                adjustmentRepository.save(adjustment);
            }

            return BookingChargeResponse.from(chargeRepository.saveAndFlush(charge));
        });
    }

    public BookingChargeResponse getChargeById(UUID id) {
        return BookingChargeResponse.from(findChargeOrThrow(id));
    }

    public BookingChargeResponse getChargeByCode(String chargeCode) {
        return readTxTemplate.execute(status ->
                chargeRepository.findByChargeCode(chargeCode)
                        .map(BookingChargeResponse::from)
                        .orElseThrow(() -> new NoSuchElementException("Booking charge not found with code: " + chargeCode)));
    }

    public List<BookingChargeResponse> getChargesByBooking(UUID bookingId) {
        return readTxTemplate.execute(status ->
                chargeRepository.findByBookingId(bookingId).stream()
                        .map(BookingChargeResponse::from)
                        .toList());
    }

    public BookingOpenBalanceSummaryResponse getBookingOpenBalanceSummary(UUID bookingId) {
        return readTxTemplate.execute(status -> {
            List<BookingCharge> charges = chargeRepository.findByBookingId(bookingId);

            BigDecimal totalOriginal = BigDecimal.ZERO;
            BigDecimal totalPaid = BigDecimal.ZERO;
            BigDecimal totalWaived = BigDecimal.ZERO;
            BigDecimal totalVoided = BigDecimal.ZERO;
            BigDecimal totalOpen = BigDecimal.ZERO;
            int activeCount = 0;
            String currency = null;
            Set<String> currencies = new HashSet<>();

            for (BookingCharge c : charges) {
                currencies.add(c.getCurrency());
                if (currency == null) {
                    currency = c.getCurrency();
                }
                totalOriginal = totalOriginal.add(c.getOriginalAmount());
                if (c.getStatus() != ChargeStatus.CANCELLED) {
                    totalPaid = totalPaid.add(c.getPaidAmount());
                    totalWaived = totalWaived.add(c.getWaivedAmount());
                    totalOpen = totalOpen.add(c.getOutstandingBalance());
                    if (c.getStatus() == ChargeStatus.OPEN || c.getStatus() == ChargeStatus.PARTIALLY_PAID) {
                        activeCount++;
                    }
                } else {
                    totalVoided = totalVoided.add(c.getVoidedAmount());
                }
            }

            if (currencies.size() > 1) {
                throw new IllegalStateException("Multiple currencies found across charges for booking: " + bookingId + " -> " + currencies);
            }

            if (currency == null) {
                currency = "VND";
            }

            return new BookingOpenBalanceSummaryResponse(
                    bookingId,
                    totalOriginal,
                    totalPaid,
                    totalWaived,
                    totalVoided,
                    totalOpen,
                    currency,
                    activeCount,
                    charges.stream().map(BookingChargeResponse::from).toList()
            );
        });
    }

    public BookingCharge findChargeOrThrow(UUID id) {
        Optional<BookingCharge> found = readTxTemplate.execute(status -> chargeRepository.findById(id));
        if (found == null || found.isEmpty()) {
            throw new NoSuchElementException("Booking charge not found with id: " + id);
        }
        return found.get();
    }

    private void verifyCreatePayloadMatches(BookingCharge existing, CreateBookingChargeCommand cmd) {
        boolean match = existing.getFinancePaymentRequestId().equals(cmd.financePaymentRequestId())
                && existing.getOriginalAmount().compareTo(cmd.originalAmount()) == 0
                && existing.getBookingId().equals(cmd.bookingId())
                && existing.getCustomerId().equals(cmd.customerId());
        if (!match) {
            throw new IllegalStateException("Idempotency key collision with differing payload: " + cmd.idempotencyKey());
        }
    }
}
