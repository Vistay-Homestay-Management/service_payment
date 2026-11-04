package com.vistay.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_charge_allocations", schema = "app")
public class BookingChargeAllocation {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "charge_id", nullable = false)
    private UUID chargeId;

    @Column(name = "finance_allocation_id", nullable = false, unique = true)
    private UUID financeAllocationId;

    @Column(name = "payment_transaction_id", nullable = false)
    private UUID paymentTransactionId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "allocated_by_user_id")
    private UUID allocatedByUserId;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BookingChargeAllocation() {
    }

    public BookingChargeAllocation(UUID chargeId,
                                  UUID financeAllocationId,
                                  UUID paymentTransactionId,
                                  BigDecimal allocatedAmount,
                                  String currency,
                                  UUID allocatedByUserId,
                                  String note) {
        if (chargeId == null) {
            throw new IllegalArgumentException("Charge ID cannot be null");
        }
        if (financeAllocationId == null) {
            throw new IllegalArgumentException("Finance allocation ID cannot be null");
        }
        if (paymentTransactionId == null) {
            throw new IllegalArgumentException("Payment transaction ID cannot be null");
        }
        if (allocatedAmount == null || allocatedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Allocated amount must be greater than zero");
        }
        this.chargeId = chargeId;
        this.financeAllocationId = financeAllocationId;
        this.paymentTransactionId = paymentTransactionId;
        this.allocatedAmount = allocatedAmount;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "VND";
        this.allocatedByUserId = allocatedByUserId;
        this.note = note;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getChargeId() { return chargeId; }
    public UUID getFinanceAllocationId() { return financeAllocationId; }
    public UUID getPaymentTransactionId() { return paymentTransactionId; }
    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public String getCurrency() { return currency; }
    public UUID getAllocatedByUserId() { return allocatedByUserId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
