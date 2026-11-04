package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentIntent;
import com.vistay.payment.domain.VietQrPresentation;
import java.time.Instant;
import java.util.UUID;

public record PaymentIntentView(
    UUID id,
    String intentCode,
    UUID financePaymentRequestId,
    String amount,
    String currency,
    String status,
    String qrPayload,
    String qrImageDataUri,
    Instant expiresAt,
    long version
) {
    static PaymentIntentView from(PaymentIntent intent, VietQrPresentation qr) {
        return new PaymentIntentView(
            intent.getId(), intent.getIntentCode(), intent.getFinancePaymentRequestId(),
            intent.getAmount().toPlainString(), intent.getCurrency(), intent.getStatus().name(),
            qr == null ? null : qr.getQrPayload(), qr == null ? null : qr.getQrImageDataUri(),
            intent.getExpiresAt(), intent.getVersion());
    }
}
