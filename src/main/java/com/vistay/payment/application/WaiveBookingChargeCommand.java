package com.vistay.payment.application;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record WaiveBookingChargeCommand(
        @NotNull @DecimalMin("0.01") BigDecimal waiveAmount,
        @NotBlank @Size(max = 500) String reason
) {
}
