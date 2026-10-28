package com.vistay.payment.application;

import com.vistay.payment.domain.BookingCharge;
import com.vistay.payment.domain.BookingChargeRepository;
import com.vistay.payment.domain.ChargeStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional
public class BookingChargeService {

    private final BookingChargeRepository chargeRepository;
    private final FinancePaymentInstructionClient financeClient;

    public BookingChargeService(BookingChargeRepository chargeRepository,
                                @Autowired(required = false) FinancePaymentInstructionClient financeClient) {
        this.chargeRepository = chargeRepository;
        this.financeClient = financeClient;
    }

    public BookingChargeResponse createCharge(CreateBookingChargeCommand cmd) {
        return createCharge(cmd, null);
    }

    public BookingChargeResponse createCharge(CreateBookingChargeCommand cmd, UUID actorId) {
        var existing = chargeRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return BookingChargeResponse.from(existing.get());
        }

        if (chargeRepository.existsByChargeCode(cmd.chargeCode())) {
            throw new IllegalArgumentException("Charge code already exists: " + cmd.chargeCode());
        }

        // Validate with Finance Payment Instruction if present
        if (cmd.financePaymentRequestId() != null && financeClient != null) {
            FinancePaymentInstruction instruction = financeClient.get(cmd.financePaymentRequestId());
            if (instruction == null) {
                throw new IllegalArgumentException("Finance payment instruction not found: " + cmd.financePaymentRequestId());
            }
            if (instruction.expiresAt() != null && instruction.expiresAt().isBefore(Instant.now())) {
                throw new IllegalArgumentException("Finance payment instruction is expired");
            }
            if (cmd.originalAmount() != null && cmd.originalAmount().compareTo(instruction.amount()) != 0) {
                throw new IllegalArgumentException("Charge amount (" + cmd.originalAmount() + ") does not match Finance instruction amount (" + instruction.amount() + ")");
            }
            if (cmd.bookingId() != null && !cmd.bookingId().equals(instruction.bookingId())) {
                throw new IllegalArgumentException("Booking ID does not match Finance instruction booking ID");
            }
        }

        UUID createdBy = actorId != null ? actorId : cmd.createdByAdminUserId();

        BookingCharge charge = new BookingCharge(
                cmd.chargeCode(),
                cmd.financePaymentRequestId(),
                cmd.bookingId(),
                cmd.customerId(),
                cmd.chargeType(),
                cmd.originalAmount(),
                cmd.currency(),
                cmd.dueDate(),
                cmd.description(),
                createdBy,
                cmd.idempotencyKey()
        );

        return BookingChargeResponse.from(chargeRepository.save(charge));
    }

    public BookingChargeResponse applyPayment(UUID chargeId, ApplyPaymentToChargeCommand cmd) {
        BookingCharge charge = findChargeOrThrow(chargeId);
        charge.applyPayment(cmd.paymentAmount(), cmd.paymentTransactionId());
        return BookingChargeResponse.from(chargeRepository.save(charge));
    }

    public BookingChargeResponse waiveCharge(UUID chargeId, WaiveBookingChargeCommand cmd) {
        return waiveCharge(chargeId, cmd, null);
    }

    public BookingChargeResponse waiveCharge(UUID chargeId, WaiveBookingChargeCommand cmd, UUID actorId) {
        BookingCharge charge = findChargeOrThrow(chargeId);
        UUID approverId = actorId != null ? actorId : cmd.approverAdminId();
        if (approverId == null) {
            throw new IllegalArgumentException("Approver admin user ID cannot be null");
        }
        charge.waive(cmd.waiveAmount(), approverId, cmd.reason());
        return BookingChargeResponse.from(chargeRepository.save(charge));
    }

    public BookingChargeResponse cancelCharge(UUID chargeId, CancelBookingChargeCommand cmd) {
        return cancelCharge(chargeId, cmd, null);
    }

    public BookingChargeResponse cancelCharge(UUID chargeId, CancelBookingChargeCommand cmd, UUID actorId) {
        BookingCharge charge = findChargeOrThrow(chargeId);
        charge.cancel(actorId, cmd.reason());
        return BookingChargeResponse.from(chargeRepository.save(charge));
    }

    @Transactional(readOnly = true)
    public BookingChargeResponse getChargeById(UUID id) {
        return BookingChargeResponse.from(findChargeOrThrow(id));
    }

    @Transactional(readOnly = true)
    public BookingChargeResponse getChargeByCode(String chargeCode) {
        return chargeRepository.findByChargeCode(chargeCode)
                .map(BookingChargeResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Booking charge not found with code: " + chargeCode));
    }

    @Transactional(readOnly = true)
    public List<BookingChargeResponse> getChargesByBooking(UUID bookingId) {
        return chargeRepository.findByBookingId(bookingId).stream()
                .map(BookingChargeResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingOpenBalanceSummaryResponse getBookingOpenBalanceSummary(UUID bookingId) {
        List<BookingCharge> charges = chargeRepository.findByBookingId(bookingId);

        BigDecimal totalOriginal = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalWaived = BigDecimal.ZERO;
        BigDecimal totalOpen = BigDecimal.ZERO;
        int activeCount = 0;
        String currency = "VND";

        for (BookingCharge c : charges) {
            currency = c.getCurrency();
            if (c.getStatus() != ChargeStatus.CANCELLED) {
                totalOriginal = totalOriginal.add(c.getOriginalAmount());
                totalPaid = totalPaid.add(c.getPaidAmount());
                totalWaived = totalWaived.add(c.getWaivedAmount());
                totalOpen = totalOpen.add(c.getOutstandingBalance());
                if (c.getStatus() == ChargeStatus.OPEN || c.getStatus() == ChargeStatus.PARTIALLY_PAID) {
                    activeCount++;
                }
            }
        }

        return new BookingOpenBalanceSummaryResponse(
                bookingId,
                totalOriginal,
                totalPaid,
                totalWaived,
                totalOpen,
                currency,
                activeCount,
                charges.stream().map(BookingChargeResponse::from).toList()
        );
    }

    private BookingCharge findChargeOrThrow(UUID id) {
        return chargeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Booking charge not found with id: " + id));
    }
}
