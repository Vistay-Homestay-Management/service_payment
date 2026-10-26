package com.vistay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentIntentTest {
    @Test
    void progressesFromCreatedToQrReady() {
        var intent = intent();
        intent.markQrReady();
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.QR_READY);
    }

    @Test
    void cancellationRequiresMatchingVersionAndReason() {
        var intent = intent();
        assertThatThrownBy(() -> intent.cancel("changed", 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> intent.cancel(" ", 0)).isInstanceOf(IllegalArgumentException.class);
        intent.cancel("booking cancelled", 0);
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.CANCELLED);
        assertThatThrownBy(intent::markQrReady).isInstanceOf(IllegalStateException.class);
    }

    private static PaymentIntent intent() {
        return PaymentIntent.create(UUID.randomUUID(), null, null, BigDecimal.valueOf(100_000), "VND",
            "Deposit", "customer", Instant.now().plusSeconds(900), "test-key", PaymentActivationMode.GENERATE_ONLY);
    }
}
