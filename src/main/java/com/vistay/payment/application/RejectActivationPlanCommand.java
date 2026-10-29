package com.vistay.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectActivationPlanCommand(
        @NotBlank(message = "Rejection reason cannot be blank")
        @Size(max = 1000)
        String reason
) {
}

