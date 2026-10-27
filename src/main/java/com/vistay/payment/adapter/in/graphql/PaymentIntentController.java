package com.vistay.payment.adapter.in.graphql;

import com.vistay.payment.application.PaymentIntentService;
import com.vistay.payment.application.PaymentIntentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class PaymentIntentController {
    private final PaymentIntentService service;

    public PaymentIntentController(PaymentIntentService service) {
        this.service = service;
    }

    @QueryMapping
    PaymentIntentView paymentIntent(@Argument UUID id) {
        return service.get(id);
    }

    @MutationMapping
    PaymentIntentView createVietQrPaymentIntent(@Argument @Valid CreateInput input) {
        return service.create(input.financePaymentRequestId(), input.idempotencyKey());
    }

    @MutationMapping
    PaymentIntentView cancelPaymentIntent(@Argument @Valid CancelInput input) {
        return service.cancel(input.paymentIntentId(), input.expectedVersion(), input.reason());
    }

    record CreateInput(UUID financePaymentRequestId, @NotBlank @Size(max = 160) String idempotencyKey) {}
    record CancelInput(UUID paymentIntentId, long expectedVersion, @NotBlank @Size(max = 500) String reason) {}
}
