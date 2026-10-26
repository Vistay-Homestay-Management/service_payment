package com.vistay.payment.adapter.out.grpc;

import com.vistay.contracts.finance.v1.FinancePaymentInstructionServiceGrpc;
import com.vistay.contracts.finance.v1.GetPaymentInstructionRequest;
import com.vistay.payment.application.FinancePaymentInstruction;
import com.vistay.payment.application.FinancePaymentInstructionClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcFinancePaymentInstructionClient implements FinancePaymentInstructionClient {
    private final FinancePaymentInstructionServiceGrpc.FinancePaymentInstructionServiceBlockingStub stub;
    private final Duration deadline;

    public GrpcFinancePaymentInstructionClient(
        GrpcChannelFactory channels,
        @Value("${vistay.payment.finance-deadline:PT2S}") Duration deadline) {
        this.stub = FinancePaymentInstructionServiceGrpc.newBlockingStub(channels.createChannel("finance"));
        this.deadline = deadline;
    }

    @Override
    public FinancePaymentInstruction get(UUID financePaymentRequestId) {
        var response = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
            .getPaymentInstruction(GetPaymentInstructionRequest.newBuilder()
                .setFinancePaymentRequestId(financePaymentRequestId.toString())
                .build());
        var money = response.getAmount();
        var amount = BigDecimal.valueOf(money.getMinorUnits());
        return new FinancePaymentInstruction(
            UUID.fromString(response.getFinancePaymentRequestId()),
            response.hasBookingId() ? UUID.fromString(response.getBookingId()) : null,
            response.hasCustomerId() ? UUID.fromString(response.getCustomerId()) : null,
            amount,
            money.getCurrency(),
            response.getPurpose(),
            response.getPayerReference(),
            Instant.ofEpochSecond(response.getExpiresAt().getSeconds(), response.getExpiresAt().getNanos()));
    }
}
