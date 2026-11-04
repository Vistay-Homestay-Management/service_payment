package com.vistay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.payment.domain.PaymentActivationMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CallbackSecurityServiceTest {
    private static final String CLIENT_ID = "vietqr-local";
    private static final String CLIENT_SECRET = "callback-secret";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    @Test
    void storesOnlyHashedTokenWithBoundedTtl() {
        var fixture = enabledFixture();
        var issued = fixture.service().issue(basic(CLIENT_ID, CLIENT_SECRET));

        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.expiresIn()).isEqualTo(TOKEN_TTL.toSeconds());
        verify(fixture.values()).set(
            "payment:callback:token:" + CallbackSecurityService.sha256(issued.accessToken()),
            "active", TOKEN_TTL);
        verify(fixture.values(), never()).set(eq(issued.accessToken()), any(), any(Duration.class));
    }

    @Test
    void rejectsWrongCredentialsWithoutWritingSecurityState() {
        var fixture = enabledFixture();
        assertThatThrownBy(() -> fixture.service().issue(basic(CLIENT_ID, "wrong")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(fixture.values(), never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void acceptsOnlyAnActiveHashedBearerToken() {
        var fixture = enabledFixture();
        var token = "opaque-callback-token";
        var key = "payment:callback:token:" + CallbackSecurityService.sha256(token);
        when(fixture.redis().hasKey(key)).thenReturn(true);

        fixture.service().authenticateBearer("Bearer " + token);

        verify(fixture.redis()).hasKey(key);
        assertThatThrownBy(() -> fixture.service().authenticateBearer("Bearer expired"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void reservesReplayFingerprintAtomicallyForTwentyFourHours() {
        var fixture = enabledFixture();
        when(fixture.values().setIfAbsent("payment:callback:replay:fingerprint", "seen", Duration.ofHours(24)))
            .thenReturn(true, false);

        assertThat(fixture.service().reserveReplayFingerprint("fingerprint")).isTrue();
        assertThat(fixture.service().reserveReplayFingerprint("fingerprint")).isFalse();
    }

    @Test
    void hidesCallbackSurfaceUnlessExplicitlyEnabled() {
        var redis = mock(StringRedisTemplate.class);
        var service = new CallbackSecurityService(redis, PaymentActivationMode.GENERATE_ONLY,
            CLIENT_ID, CLIENT_SECRET, TOKEN_TTL);

        assertThatThrownBy(() -> service.issue(basic(CLIENT_ID, CLIENT_SECRET)))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.reserveReplayFingerprint("fingerprint"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @SuppressWarnings("unchecked")
    private static Fixture enabledFixture() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return new Fixture(redis, values, new CallbackSecurityService(redis,
            PaymentActivationMode.CALLBACK_ENABLED, CLIENT_ID, CLIENT_SECRET, TOKEN_TTL));
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private record Fixture(StringRedisTemplate redis, ValueOperations<String, String> values,
                           CallbackSecurityService service) {}
}
