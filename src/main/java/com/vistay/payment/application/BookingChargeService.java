package com.vistay.payment.application;

import com.vistay.payment.domain.BookingCharge;
import com.vistay.payment.domain.BookingChargeRepository;
import com.vistay.payment.domain.ChargeStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional
public class BookingChargeService {

    private final BookingChargeRepository chargeRepository;

    public BookingChargeService(BookingChargeRepository chargeRepository) {
        this.chargeRepository = chargeRepository;
    }

    public BookingChargeResponse createCharge(CreateBookingChargeCommand cmd) {
        var existing = chargeRepository.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return BookingChargeResponse.from(existing.get());
        }

        if (chargeRepository.existsByChargeCode(cmd.chargeCode())) {
            throw new IllegalArgumentException("Charge code already exists: " + cmd.chargeCode());
        }

        BookingCharge charge = new BookingCharge(
                cmd.chargeCode(),
                cmd.bookingId(),
                cmd.customerId(),
                cmd.chargeType(),
                cmd.originalAmount(),
                cmd.currency(),
                cmd.dueDate(),
                cmd.description(),
                cmd.createdByAdminUserId(),
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
        BookingCharge charge = findChargeOrThrow(chargeId);
        charge.waive(cmd.waiveAmount(), cmd.approverAdminId(), cmd.reason());
        return BookingChargeResponse.from(chargeRepository.save(charge));
    }

    public BookingChargeResponse cancelCharge(UUID chargeId, CancelBookingChargeCommand cmd) {
        BookingCharge charge = findChargeOrThrow(chargeId);
        charge.cancel(cmd.reason());
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
