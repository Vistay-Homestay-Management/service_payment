package com.vistay.payment.application;

import java.util.UUID;

public interface FinanceAllocationClient {

    FinanceAllocationRecord getAllocation(UUID financeAllocationId);
}

