package com.vistay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_intents", schema = "app")
public class PaymentIntent {
    @Id
    private UUID id;
    @Column(name = "intent_code", nullable = false, unique = true, length = 40)
    private String intentCode;
    @Column(name = "finance_payment_request_id", nullable = false)
    private UUID financePaymentRequestId;
    @Column(name = "booking_id")
    private UUID bookingId;
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;
    @Column(name = "purpose_snapshot", nullable = false, length = 500)
    private String purposeSnapshot;
    @Column(name = "payer_reference", length = 160)
    private String payerReference;
    @Column(nullable = false, length = 40)
    private String provider;
    @Column(name = "provider_order_id", nullable = false, length = 13)
    private String providerOrderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentIntentStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "activation_mode", nullable = false, length = 24)
    private PaymentActivationMode activationMode;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "succeeded_at")
    private Instant succeededAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentIntent() {}

    public static PaymentIntent create(UUID financeRequestId, UUID bookingId, UUID customerId,
                                       BigDecimal amount, String currency, String purpose,
                                       String payerReference, Instant expiresAt,
                                       String idempotencyKey, PaymentActivationMode mode) {
        var intent = new PaymentIntent();
        intent.id = UUID.randomUUID();
        intent.intentCode = "VT" + intent.id.toString().replace("-", "").substring(0, 11).toUpperCase();
        intent.providerOrderId = intent.intentCode;
        intent.financePaymentRequestId = financeRequestId;
        intent.bookingId = bookingId;
        intent.customerId = customerId;
        intent.amount = amount;
        intent.currency = currency;
        intent.purposeSnapshot = purpose;
        intent.payerReference = payerReference;
        intent.provider = "VIETQR";
        intent.status = PaymentIntentStatus.CREATED;
        intent.activationMode = mode;
        intent.idempotencyKey = idempotencyKey;
        intent.expiresAt = expiresAt;
        intent.createdAt = Instant.now();
        intent.updatedAt = intent.createdAt;
        return intent;
    }

    public void markQrReady() {
        requireMutable();
        status = PaymentIntentStatus.QR_READY;
        updatedAt = Instant.now();
    }

    public void cancel(String reason, long expectedVersion) {
        if (version != expectedVersion) throw new IllegalStateException("Payment intent version conflict");
        requireMutable();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Cancellation reason is required");
        status = PaymentIntentStatus.CANCELLED;
        cancellationReason = reason.trim();
        cancelledAt = Instant.now();
        updatedAt = cancelledAt;
    }

    public void confirm(Instant occurredAt) {
        if (activationMode != PaymentActivationMode.CALLBACK_ENABLED) {
            throw new IllegalStateException("Callback confirmation is not enabled for this intent");
        }
        requireMutable();
        status = PaymentIntentStatus.SUCCEEDED;
        succeededAt = occurredAt;
        updatedAt = Instant.now();
    }

    private void requireMutable() {
        if (status == PaymentIntentStatus.SUCCEEDED || status == PaymentIntentStatus.CANCELLED || status == PaymentIntentStatus.EXPIRED) {
            throw new IllegalStateException("Payment intent is terminal: " + status);
        }
    }

    public UUID getId() { return id; }
    public String getIntentCode() { return intentCode; }
    public UUID getFinancePaymentRequestId() { return financePaymentRequestId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentIntentStatus getStatus() { return status; }
    public PaymentActivationMode getActivationMode() { return activationMode; }
    public String getProviderOrderId() { return providerOrderId; }
    public Instant getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
}
