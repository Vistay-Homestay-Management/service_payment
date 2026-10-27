package com.vistay.payment.adapter.out.http;

import com.vistay.payment.application.VietQrClient;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VietQrHttpClient implements VietQrClient {
    private final RestClient client;
    private final String bankBin;
    private final String accountNumber;
    private final String accountName;

    public VietQrHttpClient(
        RestClient.Builder builder,
        @Value("${vistay.payment.vietqr.base-url}") String baseUrl,
        @Value("${vistay.payment.vietqr.client-id}") String clientId,
        @Value("${vistay.payment.vietqr.api-key}") String apiKey,
        @Value("${vistay.payment.vietqr.bank-bin}") String bankBin,
        @Value("${vistay.payment.vietqr.account-number}") String accountNumber,
        @Value("${vistay.payment.vietqr.account-name}") String accountName) {
        this.client = builder.baseUrl(baseUrl)
            .defaultHeader("x-client-id", clientId)
            .defaultHeader("x-api-key", apiKey)
            .build();
        this.bankBin = bankBin;
        this.accountNumber = accountNumber;
        this.accountName = accountName;
    }

    @Override
    public QrResult generate(BigDecimal amount, String transferContent) {
        requireConfigured();
        var response = client.post().uri("/v2/generate")
            .body(Map.of(
                "accountNo", accountNumber,
                "accountName", accountName,
                "acqId", bankBin,
                "amount", amount.stripTrailingZeros().toPlainString(),
                "addInfo", transferContent,
                "format", "text",
                "template", "compact2"))
            .retrieve()
            .body(VietQrResponse.class);
        if (response == null || response.data() == null || response.data().qrCode() == null) {
            throw new IllegalStateException("VietQR did not return a QR payload");
        }
        return new QrResult(response.code(), response.data().qrCode(), response.data().qrDataURL(), mask(accountNumber));
    }

    private void requireConfigured() {
        if (bankBin.isBlank() || accountNumber.isBlank() || accountName.isBlank()) {
            throw new IllegalStateException("VietQR account configuration is incomplete");
        }
    }

    private static String mask(String value) {
        if (value.length() <= 4) return "****";
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    private record VietQrResponse(String code, String desc, VietQrData data) {}
    private record VietQrData(String qrCode, String qrDataURL) {}
}
