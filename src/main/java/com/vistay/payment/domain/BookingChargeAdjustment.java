package com.vistay.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_charge_adjustments", schema = "app")
public class BookingChargeAdjustment {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "charge_id", nullable = false)
    private UUID chargeId;

    @Column(name = "adjustment_type", nullable = false, length = 32)
    private String adjustmentType; // "WAIVER", "CANCELLATION"

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BookingChargeAdjustment() {
    }

    public BookingChargeAdjustment(UUID chargeId,
                                   String adjustmentType,
                                   BigDecimal amount,
                                   String reason,
                                   UUID actorUserId) {
        if (chargeId == null) {
            throw new IllegalArgumentException("Charge ID cannot be null");
        }
        if (adjustmentType == null || adjustmentType.isBlank()) {
            throw new IllegalArgumentException("Adjustment type cannot be blank");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Adjustment amount must be greater than zero");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Adjustment reason cannot be blank");
        }
        if (actorUserId == null) {
            throw new IllegalArgumentException("Actor user ID cannot be null");
        }
        this.chargeId = chargeId;
        this.adjustmentType = adjustmentType.trim().toUpperCase();
        this.amount = amount;
        this.reason = reason.trim();
        this.actorUserId = actorUserId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getChargeId() { return chargeId; }
    public String getAdjustmentType() { return adjustmentType; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
