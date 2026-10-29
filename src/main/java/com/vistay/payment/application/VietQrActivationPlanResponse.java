package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import com.vistay.payment.domain.VietQrActivationPlan;
import com.vistay.payment.domain.VietQrPlanStatus;

import java.time.Instant;
import java.util.UUID;

public record VietQrActivationPlanResponse(
        UUID id,
        String planCode,
        String merchantCode,
        String merchantName,
        String bankBin,
        String bankName,
        String bankAccountNumber,
        String bankAccountName,
        PaymentActivationMode targetActivationMode,
        VietQrPlanStatus status,
        boolean uatVerified,
        String uatTestReference,
        Instant uatVerifiedAt,
        String uatNotes,
        String callbackUrl,
        boolean callbackSecretConfigured,
        String createdByAdminUserId,
        Instant submittedAt,
        String approvedByAdminUserId,
        Instant approvedAt,
        String rejectionReason,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static VietQrActivationPlanResponse from(VietQrActivationPlan plan) {
        return new VietQrActivationPlanResponse(
                plan.getId(),
                plan.getPlanCode(),
                plan.getMerchantCode(),
                plan.getMerchantName(),
                plan.getBankBin(),
                plan.getBankName(),
                plan.getBankAccountNumber(),
                plan.getBankAccountName(),
                plan.getTargetActivationMode(),
                plan.getStatus(),
                plan.isUatVerified(),
                plan.getUatTestReference(),
                plan.getUatVerifiedAt(),
                plan.getUatNotes(),
                plan.getCallbackUrl(),
                plan.isCallbackSecretConfigured(),
                plan.getCreatedByAdminUserId(),
                plan.getSubmittedAt(),
                plan.getApprovedByAdminUserId(),
                plan.getApprovedAt(),
                plan.getRejectionReason(),
                plan.getVersion(),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }
}

