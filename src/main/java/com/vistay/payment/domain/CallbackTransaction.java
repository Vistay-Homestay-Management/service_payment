package com.vistay.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CallbackTransaction(
    String referenceNumber,
    String transactionId,
    String orderId,
    BigDecimal amount,
    String transactionType,
    String content,
    Instant transactionTime
) {}
