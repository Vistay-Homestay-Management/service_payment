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
        UUID createdByAdminUserId,
        Instant submittedAt,
        UUID approvedByAdminUserId,
        Instant approvedAt,
        String rejectionReason,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static VietQrActivationPlanResponse from(VietQrActivationPlan p) {
        return new VietQrActivationPlanResponse(
                p.getId(),
                p.getPlanCode(),
                p.getMerchantCode(),
                p.getMerchantName(),
                p.getBankBin(),
                p.getBankName(),
                p.getBankAccountNumber(),
                p.getBankAccountName(),
                p.getTargetActivationMode(),
                p.getStatus(),
                p.isUatVerified(),
                p.getUatTestReference(),
                p.getUatVerifiedAt(),
                p.getUatNotes(),
                p.getCallbackUrl(),
                p.isCallbackSecretConfigured(),
                p.getCreatedByAdminUserId(),
                p.getSubmittedAt(),
                p.getApprovedByAdminUserId(),
                p.getApprovedAt(),
                p.getRejectionReason(),
                p.getVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}

