package com.vistay.payment.application;

import com.vistay.payment.domain.BookingCharge;
import com.vistay.payment.domain.ChargeStatus;
import com.vistay.payment.domain.ChargeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookingChargeResponse(
        UUID id,
        String chargeCode,
        UUID bookingId,
        UUID customerId,
        ChargeType chargeType,
        BigDecimal originalAmount,
        BigDecimal paidAmount,
        BigDecimal waivedAmount,
        BigDecimal outstandingBalance,
        String currency,
        Instant dueDate,
        ChargeStatus status,
        String description,
        String cancellationReason,
        UUID lastPaymentTransactionId,
        UUID createdByAdminUserId,
        String idempotencyKey,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static BookingChargeResponse from(BookingCharge c) {
        return new BookingChargeResponse(
                c.getId(),
                c.getChargeCode(),
                c.getBookingId(),
                c.getCustomerId(),
                c.getChargeType(),
                c.getOriginalAmount(),
                c.getPaidAmount(),
                c.getWaivedAmount(),
                c.getOutstandingBalance(),
                c.getCurrency(),
                c.getDueDate(),
                c.getStatus(),
                c.getDescription(),
                c.getCancellationReason(),
                c.getLastPaymentTransactionId(),
                c.getCreatedByAdminUserId(),
                c.getIdempotencyKey(),
                c.getVersion(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
