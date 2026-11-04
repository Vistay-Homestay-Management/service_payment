package com.vistay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_items", schema = "app")
public class ReconciliationItem {
    @Id private UUID id;
    @Column(name = "provider_transaction_id", nullable = false, unique = true) private UUID providerTransactionId;
    @Column(name = "reason_code", nullable = false, length = 80) private String reasonCode;
    @Column(nullable = false, length = 2000) private String detail;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Version private long version;

    protected ReconciliationItem() {}

    public static ReconciliationItem open(UUID transactionId, String reasonCode, String detail) {
        var item = new ReconciliationItem();
        item.id = UUID.randomUUID();
        item.providerTransactionId = transactionId;
        item.reasonCode = reasonCode;
        item.detail = detail;
        item.status = "OPEN";
        item.createdAt = Instant.now();
        return item;
    }
}
