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
        UUID financePaymentRequestId,
        UUID bookingId,
        UUID branchId,
        UUID customerId,
        ChargeType chargeType,
        BigDecimal originalAmount,
        BigDecimal paidAmount,
        BigDecimal waivedAmount,
        BigDecimal voidedAmount,
        BigDecimal outstandingBalance,
        String currency,
        Instant dueDate,
        ChargeStatus status,
        String description,
        String cancellationReason,
        UUID cancelledByAdminUserId,
        String waiverReason,
        UUID waivedByAdminUserId,
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
                c.getFinancePaymentRequestId(),
                c.getBookingId(),
                c.getBranchId(),
                c.getCustomerId(),
                c.getChargeType(),
                c.getOriginalAmount(),
                c.getPaidAmount(),
                c.getWaivedAmount(),
                c.getVoidedAmount(),
                c.getOutstandingBalance(),
                c.getCurrency(),
                c.getDueDate(),
                c.getStatus(),
                c.getDescription(),
                c.getCancellationReason(),
                c.getCancelledByAdminUserId(),
                c.getWaiverReason(),
                c.getWaivedByAdminUserId(),
                c.getLastPaymentTransactionId(),
                c.getCreatedByAdminUserId(),
                c.getIdempotencyKey(),
                c.getVersion(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
