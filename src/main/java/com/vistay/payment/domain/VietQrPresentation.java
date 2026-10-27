package com.vistay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vietqr_presentations", schema = "app")
public class VietQrPresentation {
    @Id
    private UUID id;
    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;
    @Column(name = "provider_request_id", length = 180)
    private String providerRequestId;
    @Column(name = "qr_payload", nullable = false)
    private String qrPayload;
    @Column(name = "qr_image_data_uri")
    private String qrImageDataUri;
    @Column(name = "account_display_masked", nullable = false, length = 120)
    private String accountDisplayMasked;
    @Column(name = "transfer_content", nullable = false, length = 23)
    private String transferContent;
    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected VietQrPresentation() {}

    public static VietQrPresentation create(UUID intentId, String requestId, String payload,
                                            String image, String maskedAccount,
                                            String transferContent, Instant expiresAt) {
        var value = new VietQrPresentation();
        value.id = UUID.randomUUID();
        value.paymentIntentId = intentId;
        value.providerRequestId = requestId;
        value.qrPayload = payload;
        value.qrImageDataUri = image;
        value.accountDisplayMasked = maskedAccount;
        value.transferContent = transferContent;
        value.generatedAt = Instant.now();
        value.expiresAt = expiresAt;
        return value;
    }

    public String getQrPayload() { return qrPayload; }
    public String getQrImageDataUri() { return qrImageDataUri; }
}
