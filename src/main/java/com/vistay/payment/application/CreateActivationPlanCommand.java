package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateActivationPlanCommand(
        @NotBlank(message = "Plan code cannot be blank")
        @Size(max = 60)
        String planCode,

        @NotBlank(message = "Merchant code cannot be blank")
        @Size(max = 60)
        String merchantCode,

        @NotBlank(message = "Merchant name cannot be blank")
        @Size(max = 160)
        String merchantName,

        @NotBlank(message = "Bank BIN cannot be blank")
        @Size(max = 10)
        String bankBin,

        @NotBlank(message = "Bank name cannot be blank")
        @Size(max = 100)
        String bankName,

        @NotBlank(message = "Bank account number cannot be blank")
        @Size(max = 40)
        String bankAccountNumber,

        @NotBlank(message = "Bank account name cannot be blank")
        @Size(max = 160)
        String bankAccountName,

        @NotNull(message = "Target activation mode cannot be null")
        PaymentActivationMode targetActivationMode
) {
}

