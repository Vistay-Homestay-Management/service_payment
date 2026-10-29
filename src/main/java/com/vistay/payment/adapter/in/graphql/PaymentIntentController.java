package com.vistay.payment.adapter.in.graphql;

import com.vistay.payment.application.BookingChargeAuthorizationService;
import com.vistay.payment.application.PaymentIntentService;
import com.vistay.payment.application.PaymentIntentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class PaymentIntentController {
    private final PaymentIntentService service;
    private final BookingChargeAuthorizationService authorization;

    public PaymentIntentController(
            PaymentIntentService service,
            BookingChargeAuthorizationService authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @QueryMapping
    PaymentIntentView paymentIntent(
            @Argument UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        authorization.requirePermission(jwt, "payment:read");
        authorization.assertPaymentIntentAccess(service.access(id), jwt, "payment:read");
        return service.get(id);
    }

    @MutationMapping
    PaymentIntentView createVietQrPaymentIntent(
            @Argument @Valid CreateInput input,
            @AuthenticationPrincipal Jwt jwt) {
        authorization.requirePermission(jwt, "payment:create");
        authorization.assertPaymentIntentAccess(
            service.accessForCreate(input.financePaymentRequestId(), input.idempotencyKey()),
            jwt,
            "payment:create");
        return service.create(input.financePaymentRequestId(), input.idempotencyKey());
    }

    @MutationMapping
    PaymentIntentView cancelPaymentIntent(
            @Argument @Valid CancelInput input,
            @AuthenticationPrincipal Jwt jwt) {
        authorization.requirePermission(jwt, "payment:cancel");
        authorization.assertPaymentIntentAccess(
            service.access(input.paymentIntentId()), jwt, "payment:cancel");
        return service.cancel(input.paymentIntentId(), input.expectedVersion(), input.reason());
    }

    record CreateInput(UUID financePaymentRequestId, @NotBlank @Size(max = 160) String idempotencyKey) {}
    record CancelInput(UUID paymentIntentId, long expectedVersion, @NotBlank @Size(max = 500) String reason) {}
}
