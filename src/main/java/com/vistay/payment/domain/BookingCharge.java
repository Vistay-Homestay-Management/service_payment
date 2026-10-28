package com.vistay.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_charges", schema = "app")
public class BookingCharge {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "charge_code", nullable = false, unique = true, length = 60)
    private String chargeCode;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 32)
    private ChargeType chargeType;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal originalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "waived_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal waivedAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingBalance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ChargeStatus status = ChargeStatus.OPEN;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "last_payment_transaction_id")
    private UUID lastPaymentTransactionId;

    @Column(name = "created_by_admin_user_id")
    private UUID createdByAdminUserId;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 160)
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BookingCharge() {
    }

    public BookingCharge(String chargeCode,
                         UUID bookingId,
                         UUID customerId,
                         ChargeType chargeType,
                         BigDecimal originalAmount,
                         String currency,
                         Instant dueDate,
                         String description,
                         UUID createdByAdminUserId,
                         String idempotencyKey) {
        if (chargeCode == null || chargeCode.isBlank()) {
            throw new IllegalArgumentException("Charge code cannot be blank");
        }
        if (bookingId == null) {
            throw new IllegalArgumentException("Booking ID cannot be null");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("Customer ID cannot be null");
        }
        if (chargeType == null) {
            throw new IllegalArgumentException("Charge type cannot be null");
        }
        if (originalAmount == null || originalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Original amount must be greater than zero");
        }
        if (dueDate == null) {
            throw new IllegalArgumentException("Due date cannot be null");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }

        this.chargeCode = chargeCode.trim();
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.chargeType = chargeType;
        this.originalAmount = originalAmount;
        this.paidAmount = BigDecimal.ZERO;
        this.waivedAmount = BigDecimal.ZERO;
        this.outstandingBalance = originalAmount;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "VND";
        this.dueDate = dueDate;
        this.description = description;
        this.createdByAdminUserId = createdByAdminUserId;
        this.idempotencyKey = idempotencyKey.trim();
        this.status = ChargeStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void applyPayment(BigDecimal amount, UUID transactionId) {
        if (status != ChargeStatus.OPEN && status != ChargeStatus.PARTIALLY_PAID) {
            throw new IllegalStateException("Cannot apply payment to charge with status: " + status);
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (transactionId == null) {
            throw new IllegalArgumentException("Payment transaction ID is required for verification");
        }
        if (amount.compareTo(this.outstandingBalance) > 0) {
            throw new IllegalArgumentException("Payment amount (" + amount + ") exceeds outstanding balance (" + this.outstandingBalance + ")");
        }

        this.paidAmount = this.paidAmount.add(amount);
        this.outstandingBalance = this.outstandingBalance.subtract(amount);
        this.lastPaymentTransactionId = transactionId;

        if (this.outstandingBalance.compareTo(BigDecimal.ZERO) == 0) {
            this.status = ChargeStatus.PAID;
        } else {
            this.status = ChargeStatus.PARTIALLY_PAID;
        }
        this.updatedAt = Instant.now();
    }

    public void waive(BigDecimal waiveAmount, UUID approverAdminId, String reason) {
        if (status != ChargeStatus.OPEN && status != ChargeStatus.PARTIALLY_PAID) {
            throw new IllegalStateException("Cannot waive charge with status: " + status);
        }
        if (approverAdminId == null) {
            throw new IllegalArgumentException("Approver admin user ID cannot be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Waive reason cannot be blank");
        }
        BigDecimal amountToWaive = (waiveAmount != null && waiveAmount.compareTo(BigDecimal.ZERO) > 0)
                ? waiveAmount
                : this.outstandingBalance;

        if (amountToWaive.compareTo(this.outstandingBalance) > 0) {
            throw new IllegalArgumentException("Waive amount (" + amountToWaive + ") cannot exceed outstanding balance (" + this.outstandingBalance + ")");
        }

        this.waivedAmount = this.waivedAmount.add(amountToWaive);
        this.outstandingBalance = this.outstandingBalance.subtract(amountToWaive);

        if (this.outstandingBalance.compareTo(BigDecimal.ZERO) == 0) {
            this.status = (this.paidAmount.compareTo(BigDecimal.ZERO) > 0) ? ChargeStatus.PAID : ChargeStatus.WAIVED;
        } else {
            this.status = ChargeStatus.PARTIALLY_PAID;
        }

        this.cancellationReason = "WAIVED by " + approverAdminId + " (Amount: " + amountToWaive + "): " + reason.trim();
        this.updatedAt = Instant.now();
    }

    public void cancel(String reason) {
        if (status == ChargeStatus.PAID || status == ChargeStatus.CANCELLED || status == ChargeStatus.WAIVED) {
            throw new IllegalStateException("Cannot cancel charge with status: " + status);
        }
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Cannot cancel charge with existing paid amount (" + paidAmount + "). Must refund or adjust via Finance ledger.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason cannot be blank");
        }
        this.status = ChargeStatus.CANCELLED;
        this.outstandingBalance = BigDecimal.ZERO;
        this.waivedAmount = this.originalAmount; // Balances formula original = paid + waived + outstanding
        this.cancellationReason = reason.trim();
        this.updatedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getChargeCode() { return chargeCode; }
    public UUID getBookingId() { return bookingId; }
    public UUID getCustomerId() { return customerId; }
    public ChargeType getChargeType() { return chargeType; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public BigDecimal getWaivedAmount() { return waivedAmount; }
    public BigDecimal getOutstandingBalance() { return outstandingBalance; }
    public String getCurrency() { return currency; }
    public Instant getDueDate() { return dueDate; }
    public ChargeStatus getStatus() { return status; }
    public String getDescription() { return description; }
    public String getCancellationReason() { return cancellationReason; }
    public UUID getLastPaymentTransactionId() { return lastPaymentTransactionId; }
    public UUID getCreatedByAdminUserId() { return createdByAdminUserId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
