package com.vistay.payment.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vistay.payment.application.CallbackSecurityService;
import com.vistay.payment.application.VietQrCallbackService;
import com.vistay.payment.domain.CallbackTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/vietqr")
public class VietQrCallbackController {
    private final CallbackSecurityService security;
    private final VietQrCallbackService callbacks;

    public VietQrCallbackController(CallbackSecurityService security, VietQrCallbackService callbacks) {
        this.security = security;
        this.callbacks = callbacks;
    }

    @PostMapping("/token")
    TokenResponse issueToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        var issued = security.issue(authorization);
        return new TokenResponse(issued.accessToken(), "Bearer", issued.expiresIn());
    }

    @PostMapping("/transactions")
    Map<String, Object> receive(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                @RequestBody @Valid TransactionRequest request) {
        security.authenticateBearer(authorization);
        var result = callbacks.receive(request.toDomain());
        return Map.of("accepted", true, "duplicate", result.duplicate(), "matchStatus", result.matchStatus());
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") long expiresIn) {}

    record TransactionRequest(
        @NotBlank @Size(max = 180) String referenceNumber,
        @Size(max = 180) String transactionId,
        @Size(max = 13) String orderId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
        @NotBlank String transactionType,
        @Size(max = 500) String content,
        @NotNull Instant transactionTime
    ) {
        CallbackTransaction toDomain() {
            return new CallbackTransaction(referenceNumber, transactionId, orderId, amount,
                transactionType, content, transactionTime);
        }
    }
}
