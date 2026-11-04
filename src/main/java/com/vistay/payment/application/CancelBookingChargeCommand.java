package com.vistay.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelBookingChargeCommand(
        @NotBlank @Size(max = 500) String reason
) {
}

