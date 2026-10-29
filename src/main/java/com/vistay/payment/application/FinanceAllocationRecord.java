package com.vistay.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

public record FinanceAllocationRecord(
        UUID financeAllocationId,
        UUID financePaymentRequestId,
        UUID paymentTransactionId,
        BigDecimal allocatedAmount,
        String currency,
        String status
) {
}

