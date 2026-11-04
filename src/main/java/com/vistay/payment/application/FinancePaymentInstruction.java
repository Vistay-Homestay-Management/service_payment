package com.vistay.payment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FinancePaymentInstruction(
    UUID financePaymentRequestId,
    UUID bookingId,
    UUID customerId,
    BigDecimal amount,
    String currency,
    String purpose,
    String payerReference,
    Instant expiresAt
) {}
