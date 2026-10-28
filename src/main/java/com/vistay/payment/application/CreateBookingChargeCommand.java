package com.vistay.payment.application;

import com.vistay.payment.domain.ChargeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateBookingChargeCommand(
        @NotBlank @Size(max = 60) String chargeCode,
        UUID financePaymentRequestId,
        @NotNull UUID bookingId,
        @NotNull UUID customerId,
        @NotNull ChargeType chargeType,
        @NotNull @DecimalMin("0.01") BigDecimal originalAmount,
        String currency,
        @NotNull Instant dueDate,
        String description,
        UUID createdByAdminUserId,
        @NotBlank @Size(max = 160) String idempotencyKey
) {
    public CreateBookingChargeCommand(
            String chargeCode,
            UUID bookingId,
            UUID customerId,
            ChargeType chargeType,
            BigDecimal originalAmount,
            String currency,
            Instant dueDate,
            String description,
            UUID createdByAdminUserId,
            String idempotencyKey) {
        this(chargeCode, null, bookingId, customerId, chargeType, originalAmount, currency, dueDate, description, createdByAdminUserId, idempotencyKey);
    }
}
