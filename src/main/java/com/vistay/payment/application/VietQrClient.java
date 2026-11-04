package com.vistay.payment.application;

import java.math.BigDecimal;

public interface VietQrClient {
    QrResult generate(BigDecimal amount, String transferContent);

    record QrResult(String providerRequestId, String qrPayload, String qrImageDataUri, String maskedAccount) {}
}
