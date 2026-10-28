package com.vistay.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record WaiveBookingChargeCommand(
        BigDecimal waiveAmount,
        @NotNull UUID approverAdminId,
        @NotBlank @Size(max = 500) String reason
) {
}

