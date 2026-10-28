package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateActivationPlanCommand(
        @NotBlank @Size(max = 60) String planCode,
        @NotBlank @Size(max = 60) String merchantCode,
        @NotBlank @Size(max = 160) String merchantName,
        @NotBlank @Size(max = 10) String bankBin,
        @NotBlank @Size(max = 100) String bankName,
        @NotBlank @Size(max = 40) String bankAccountNumber,
        @NotBlank @Size(max = 160) String bankAccountName,
        @NotNull PaymentActivationMode targetActivationMode,
        @NotNull UUID createdByAdminUserId
) {
}

