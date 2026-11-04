package com.vistay.payment.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfigureCallbackCommand(
        @NotBlank(message = "Callback URL cannot be blank")
        @Size(max = 500)
        String callbackUrl,

        boolean secretConfigured
) {
}

