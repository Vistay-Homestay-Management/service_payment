package com.vistay.payment.application;

import jakarta.validation.constraints.Size;

public record SuspendActivationPlanCommand(
        @Size(max = 1000)
        String reason
) {
}

