package com.vistay.payment.domain;

public enum PaymentIntentStatus {
    CREATED,
    QR_READY,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED
}
