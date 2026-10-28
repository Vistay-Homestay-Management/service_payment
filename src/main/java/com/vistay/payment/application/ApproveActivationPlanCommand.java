package com.vistay.payment.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ApproveActivationPlanCommand(
        @NotNull UUID approvedByAdminUserId
) {
}

