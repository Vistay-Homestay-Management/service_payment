package com.vistay.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vietqr_activation_plans", schema = "app")
public class VietQrActivationPlan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "plan_code", nullable = false, unique = true, length = 60)
    private String planCode;

    @Column(name = "merchant_code", nullable = false, length = 60)
    private String merchantCode;

    @Column(name = "merchant_name", nullable = false, length = 160)
    private String merchantName;

    @Column(name = "bank_bin", nullable = false, length = 10)
    private String bankBin;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    @Column(name = "bank_account_number", nullable = false, length = 40)
    private String bankAccountNumber;

    @Column(name = "bank_account_name", nullable = false, length = 160)
    private String bankAccountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_activation_mode", nullable = false, length = 24)
    private PaymentActivationMode targetActivationMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private VietQrPlanStatus status = VietQrPlanStatus.DRAFT;

    @Column(name = "uat_verified", nullable = false)
    private boolean uatVerified = false;

    @Column(name = "uat_test_reference", length = 180)
    private String uatTestReference;

    @Column(name = "uat_verified_at")
    private Instant uatVerifiedAt;

    @Column(name = "uat_notes", columnDefinition = "text")
    private String uatNotes;

    @Column(name = "callback_url", length = 500)
    private String callbackUrl;

    @Column(name = "callback_secret_configured", nullable = false)
    private boolean callbackSecretConfigured = false;

    @Column(name = "created_by_admin_user_id", nullable = false, updatable = false, length = 100)
    private String createdByAdminUserId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by_admin_user_id", length = 100)
    private String approvedByAdminUserId;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected VietQrActivationPlan() {
    }

    public VietQrActivationPlan(String planCode,
                                String merchantCode,
                                String merchantName,
                                String bankBin,
                                String bankName,
                                String bankAccountNumber,
                                String bankAccountName,
                                PaymentActivationMode targetActivationMode,
                                String createdByAdminUserId) {
        if (planCode == null || planCode.isBlank()) {
            throw new IllegalArgumentException("Plan code cannot be blank");
        }
        if (merchantCode == null || merchantCode.isBlank()) {
            throw new IllegalArgumentException("Merchant code cannot be blank");
        }
        if (merchantName == null || merchantName.isBlank()) {
            throw new IllegalArgumentException("Merchant name cannot be blank");
        }
        if (bankBin == null || bankBin.isBlank()) {
            throw new IllegalArgumentException("Bank BIN cannot be blank");
        }
        if (bankName == null || bankName.isBlank()) {
            throw new IllegalArgumentException("Bank name cannot be blank");
        }
        if (bankAccountNumber == null || bankAccountNumber.isBlank()) {
            throw new IllegalArgumentException("Bank account number cannot be blank");
        }
        if (bankAccountName == null || bankAccountName.isBlank()) {
            throw new IllegalArgumentException("Bank account name cannot be blank");
        }
        if (targetActivationMode == null) {
            throw new IllegalArgumentException("Target activation mode cannot be null");
        }
        if (createdByAdminUserId == null || createdByAdminUserId.isBlank()) {
            throw new IllegalArgumentException("Creator actor identity cannot be null or blank");
        }

        this.planCode = planCode.trim();
        this.merchantCode = merchantCode.trim();
        this.merchantName = merchantName.trim();
        this.bankBin = bankBin.trim();
        this.bankName = bankName.trim();
        this.bankAccountNumber = bankAccountNumber.trim();
        this.bankAccountName = bankAccountName.trim();
        this.targetActivationMode = targetActivationMode;
        this.createdByAdminUserId = createdByAdminUserId.trim();
        this.status = VietQrPlanStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void recordUatEvidence(String testReference, String notes) {
        if (testReference == null || testReference.isBlank()) {
            throw new IllegalArgumentException("UAT test reference cannot be blank");
        }
        this.uatVerified = true;
        this.uatTestReference = testReference.trim();
        this.uatVerifiedAt = Instant.now();
        this.uatNotes = notes != null ? notes.trim() : null;
        this.updatedAt = Instant.now();
    }

    public void configureCallback(String callbackUrl, boolean secretConfigured) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("Callback URL cannot be blank");
        }
        String trimmed = callbackUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException("Callback URL must use public HTTPS protocol: " + trimmed);
        }
        this.callbackUrl = trimmed;
        this.callbackSecretConfigured = secretConfigured;
        this.updatedAt = Instant.now();
    }

    public void submitForApproval() {
        if (status != VietQrPlanStatus.DRAFT && status != VietQrPlanStatus.REJECTED) {
            throw new IllegalStateException("Only DRAFT or REJECTED plans can be submitted. Current: " + status);
        }
        this.status = VietQrPlanStatus.PENDING_APPROVAL;
        this.submittedAt = Instant.now();
        this.rejectionReason = null;
        this.updatedAt = Instant.now();
    }

    public void approve(String checkerActorId) {
        if (status != VietQrPlanStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL plans can be approved. Current: " + status);
        }
        if (checkerActorId == null || checkerActorId.isBlank()) {
            throw new IllegalArgumentException("Approver ID cannot be null or blank");
        }
        String trimmedApprover = checkerActorId.trim();
        if (Objects.equals(this.createdByAdminUserId, trimmedApprover)) {
            throw new IllegalStateException("Maker-Checker violation: Creator cannot approve their own VietQR activation plan");
        }

        if (this.targetActivationMode == PaymentActivationMode.CALLBACK_ENABLED) {
            if (!this.uatVerified) {
                throw new IllegalStateException("Cannot approve CALLBACK_ENABLED plan: UAT test evidence has not been verified");
            }
            if (!this.callbackSecretConfigured) {
                throw new IllegalStateException("Cannot approve CALLBACK_ENABLED plan: Callback webhook secret is not configured");
            }
            if (this.callbackUrl == null || !this.callbackUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalStateException("Cannot approve CALLBACK_ENABLED plan: Callback URL must be a valid HTTPS endpoint");
            }
        }

        this.status = VietQrPlanStatus.APPROVED;
        this.approvedByAdminUserId = trimmedApprover;
        this.approvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void activate(String operatorActorId) {
        if (status != VietQrPlanStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED plans can be activated. Current: " + status);
        }
        if (this.targetActivationMode == PaymentActivationMode.CALLBACK_ENABLED) {
            if (!this.uatVerified || !this.callbackSecretConfigured || this.callbackUrl == null || !this.callbackUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalStateException("Cannot activate CALLBACK_ENABLED plan: prerequisite UAT and HTTPS gates not met");
            }
        }
        this.status = VietQrPlanStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void reject(String checkerActorId, String reason) {
        if (status != VietQrPlanStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only PENDING_APPROVAL plans can be rejected. Current: " + status);
        }
        if (checkerActorId == null || checkerActorId.isBlank()) {
            throw new IllegalArgumentException("Reviewer ID cannot be null or blank");
        }
        String trimmedReviewer = checkerActorId.trim();
        if (Objects.equals(this.createdByAdminUserId, trimmedReviewer)) {
            throw new IllegalStateException("Maker-Checker violation: Creator cannot reject their own VietQR activation plan");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rejection reason cannot be blank");
        }

        this.status = VietQrPlanStatus.REJECTED;
        this.approvedByAdminUserId = trimmedReviewer;
        this.rejectionReason = reason.trim();
        this.updatedAt = Instant.now();
    }

    public void suspend(String operatorActorId, String reason) {
        if (status != VietQrPlanStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE plans can be suspended. Current: " + status);
        }
        this.status = VietQrPlanStatus.SUSPENDED;
        this.rejectionReason = reason != null ? reason.trim() : null;
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() {
        return id;
    }

    public String getPlanCode() {
        return planCode;
    }

    public String getMerchantCode() {
        return merchantCode;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getBankBin() {
        return bankBin;
    }

    public String getBankName() {
        return bankName;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public String getBankAccountName() {
        return bankAccountName;
    }

    public PaymentActivationMode getTargetActivationMode() {
        return targetActivationMode;
    }

    public VietQrPlanStatus getStatus() {
        return status;
    }

    public boolean isUatVerified() {
        return uatVerified;
    }

    public String getUatTestReference() {
        return uatTestReference;
    }

    public Instant getUatVerifiedAt() {
        return uatVerifiedAt;
    }

    public String getUatNotes() {
        return uatNotes;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public boolean isCallbackSecretConfigured() {
        return callbackSecretConfigured;
    }

    public String getCreatedByAdminUserId() {
        return createdByAdminUserId;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getApprovedByAdminUserId() {
        return approvedByAdminUserId;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

