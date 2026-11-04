package com.vistay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events", schema = "app")
public class OutboxEvent {
    @Id private UUID id;
    @Column(name = "aggregate_type", nullable = false, length = 100) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false) private UUID aggregateId;
    @Column(name = "aggregate_version", nullable = false) private long aggregateVersion;
    @Column(name = "event_type", nullable = false, length = 160) private String eventType;
    @Column(name = "event_version", nullable = false) private int eventVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> payload;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "publish_attempts", nullable = false) private int publishAttempts;
    @Column(name = "last_error") private String lastError;
    @Column(name = "correlation_id", length = 100) private String correlationId;
    @Column(name = "causation_id", length = 100) private String causationId;

    protected OutboxEvent() {}

    public static OutboxEvent paymentTransaction(ProviderTransaction transaction,
                                                PaymentIntent intent,
                                                CallbackTransaction callback,
                                                String matchStatus,
                                                String mismatchReason) {
        var event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.aggregateType = "PaymentIntent";
        event.aggregateId = intent == null ? transaction.getId() : intent.getId();
        event.aggregateVersion = intent == null ? 1 : Math.max(1, intent.getVersion() + 1);
        event.eventType = "MATCHED".equals(matchStatus)
            ? "vistay.payment.transaction-confirmed" : "vistay.payment.transaction-unmatched";
        event.eventVersion = 1;
        event.occurredAt = Instant.now();
        event.availableAt = event.occurredAt;
        event.correlationId = UUID.randomUUID().toString();
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("providerTransactionId", transaction.getId().toString());
        data.put("paymentIntentId", intent == null ? null : intent.getId().toString());
        data.put("financePaymentRequestId", intent == null ? null : intent.getFinancePaymentRequestId().toString());
        data.put("referenceNumber", callback.referenceNumber());
        data.put("amount", callback.amount().toPlainString());
        data.put("currency", "VND");
        data.put("matchStatus", matchStatus);
        data.put("mismatchReason", mismatchReason);
        data.put("occurredAt", callback.transactionTime().toString());
        var envelope = new java.util.LinkedHashMap<String, Object>();
        envelope.put("eventId", event.id.toString());
        envelope.put("type", event.eventType);
        envelope.put("version", event.eventVersion);
        envelope.put("aggregateId", event.aggregateId.toString());
        envelope.put("aggregateVersion", event.aggregateVersion);
        envelope.put("correlationId", event.correlationId);
        envelope.put("causationId", null);
        envelope.put("occurredAt", event.occurredAt.toString());
        envelope.put("data", data);
        event.payload = envelope;
        return event;
    }

    public void markPublished() {
        publishedAt = Instant.now();
        publishAttempts++;
        lastError = null;
    }

    public void markFailed(Throwable error) {
        publishAttempts++;
        lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage().substring(0, Math.min(2000, error.getMessage().length()));
        availableAt = Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, publishAttempts)));
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public Map<String, Object> getPayload() { return payload; }
}
