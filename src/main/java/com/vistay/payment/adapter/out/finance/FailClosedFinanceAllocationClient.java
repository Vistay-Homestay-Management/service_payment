package com.vistay.payment.adapter.out.finance;

import com.vistay.payment.application.FinanceAllocationClient;
import com.vistay.payment.application.FinanceAllocationRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fail-closed adapter for Finance Allocation verification.
 *
 * Upstream service_finance has not yet exposed an allocation query contract in platform_contracts.
 * In accordance with financial zero-trust, this adapter ensures Payment fails closed rather than
 * accepting unverified caller assertions.
 */
@Component
public class FailClosedFinanceAllocationClient implements FinanceAllocationClient {

    @Override
    public FinanceAllocationRecord getAllocation(UUID financeAllocationId) {
        throw new UnsupportedOperationException(
                "Finance allocation query contract is pending upstream service_finance provisioning. "
                + "Production payment collection allocation remains fail-closed until Finance publishes the contract."
        );
    }
}

