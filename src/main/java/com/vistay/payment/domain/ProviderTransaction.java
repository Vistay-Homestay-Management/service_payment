package com.vistay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_transactions", schema = "app")
public class ProviderTransaction {
    @Id private UUID id;
    @Column(nullable = false, length = 40) private String provider;
    @Column(name = "reference_number", nullable = false, length = 180) private String referenceNumber;
    @Column(name = "provider_transaction_id", length = 180) private String providerTransactionId;
    @Column(name = "payment_intent_id") private UUID paymentIntentId;
    @Column(name = "provider_order_id", length = 13) private String providerOrderId;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 12) private String direction;
    @Column(name = "transfer_content", length = 500) private String transferContent;
    @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;
    @Column(name = "match_status", nullable = false, length = 24) private String matchStatus;
    @Column(name = "mismatch_reason", length = 500) private String mismatchReason;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "matched_at") private Instant matchedAt;

    protected ProviderTransaction() {}

    public static ProviderTransaction matched(CallbackTransaction value, PaymentIntent intent, String payloadHash) {
        return create(value, intent.getId(), "MATCHED", null, payloadHash, Instant.now());
    }

    public static ProviderTransaction unmatched(CallbackTransaction value, String reason, String payloadHash) {
        return create(value, null, "UNMATCHED", reason, payloadHash, null);
    }

    private static ProviderTransaction create(CallbackTransaction value, UUID intentId, String status,
                                              String reason, String hash, Instant matchedAt) {
        var tx = new ProviderTransaction();
        tx.id = UUID.randomUUID();
        tx.provider = "VIETQR";
        tx.referenceNumber = value.referenceNumber();
        tx.providerTransactionId = value.transactionId();
        tx.paymentIntentId = intentId;
        tx.providerOrderId = value.orderId();
        tx.amount = value.amount();
        tx.currency = "VND";
        tx.direction = value.transactionType();
        tx.transferContent = value.content();
        tx.payloadSha256 = hash;
        tx.matchStatus = status;
        tx.mismatchReason = reason;
        tx.occurredAt = value.transactionTime();
        tx.receivedAt = Instant.now();
        tx.matchedAt = matchedAt;
        return tx;
    }

    public UUID getId() { return id; }
    public String getMatchStatus() { return matchStatus; }
}
