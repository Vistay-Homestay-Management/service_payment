package com.vistay.payment.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ApplyPaymentToChargeCommand(
        @NotNull UUID financeAllocationId,
        @NotNull UUID paymentTransactionId,
        @NotNull @DecimalMin("0.01") BigDecimal paymentAmount,
        String note
) {
    public ApplyPaymentToChargeCommand(UUID financeAllocationId, UUID paymentTransactionId, BigDecimal paymentAmount) {
        this(financeAllocationId, paymentTransactionId, paymentAmount, null);
    }
}
