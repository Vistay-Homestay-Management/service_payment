package com.vistay.payment.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ApplyPaymentToChargeCommand(
        @NotNull @DecimalMin("0.01") BigDecimal paymentAmount,
        @NotNull UUID paymentTransactionId
) {
}
