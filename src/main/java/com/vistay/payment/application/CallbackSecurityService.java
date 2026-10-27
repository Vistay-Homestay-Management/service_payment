package com.vistay.payment.application;

import com.vistay.payment.domain.PaymentActivationMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CallbackSecurityService {
    private final StringRedisTemplate redis;
    private final PaymentActivationMode activationMode;
    private final String expectedClientId;
    private final String expectedSecret;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();

    public CallbackSecurityService(
        StringRedisTemplate redis,
        @Value("${vistay.payment.activation-mode:DISABLED}") PaymentActivationMode activationMode,
        @Value("${vistay.payment.vietqr.callback-client-id}") String expectedClientId,
        @Value("${vistay.payment.vietqr.callback-client-secret}") String expectedSecret,
        @Value("${vistay.payment.vietqr.callback-token-ttl:PT5M}") Duration tokenTtl) {
        this.redis = redis;
        this.activationMode = activationMode;
        this.expectedClientId = expectedClientId;
        this.expectedSecret = expectedSecret;
        this.tokenTtl = tokenTtl;
    }

    public IssuedToken issue(String authorization) {
        requireEnabled();
        var pair = decodeBasic(authorization);
        if (expectedClientId.isBlank() || expectedSecret.isBlank()
            || !constantTimeEquals(expectedClientId, pair[0])
            || !constantTimeEquals(expectedSecret, pair[1])) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback credentials");
        }
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(tokenKey(token), "active", tokenTtl);
        return new IssuedToken(token, tokenTtl.toSeconds());
    }

    public void authenticateBearer(String authorization) {
        requireEnabled();
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token is required");
        }
        var token = authorization.substring(7).trim();
        if (token.isEmpty() || !Boolean.TRUE.equals(redis.hasKey(tokenKey(token)))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Callback token is invalid or expired");
        }
    }

    public boolean reserveReplayFingerprint(String fingerprint) {
        requireEnabled();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
            "payment:callback:replay:" + fingerprint, "seen", Duration.ofHours(24)));
    }

    public void requireEnabled() {
        if (activationMode != PaymentActivationMode.CALLBACK_ENABLED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String tokenKey(String token) { return "payment:callback:token:" + sha256(token); }

    private static String[] decodeBasic(String authorization) {
        try {
            if (authorization == null || !authorization.startsWith("Basic ")) throw new IllegalArgumentException();
            var decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            var separator = decoded.indexOf(':');
            if (separator < 1) throw new IllegalArgumentException();
            return new String[]{decoded.substring(0, separator), decoded.substring(separator + 1)};
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Basic credentials are required");
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record IssuedToken(String accessToken, long expiresIn) {}
}
