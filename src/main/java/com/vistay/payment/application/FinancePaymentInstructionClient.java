package com.vistay.payment.application;

import java.util.UUID;

public interface FinancePaymentInstructionClient {
    FinancePaymentInstruction get(UUID financePaymentRequestId);
}
