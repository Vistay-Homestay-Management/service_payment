# Redis security-state and replay runbook

## Boundary

Redis is not a system of record. Payment may store only short-lived callback
security state:

- `payment:callback:token:<sha256>`: opaque bearer-token hash, five-minute TTL by default;
- `payment:callback:replay:<fingerprint>`: atomic replay reservation, 24-hour TTL.

Payment intents, provider transactions, reconciliation state and accounting facts
remain in PostgreSQL. Raw bearer tokens, credentials, payment records and PII must
never be Redis values or keys.

## Activation and verification

Callback routes return HTTP 404 unless `PAYMENT_ACTIVATION_MODE=CALLBACK_ENABLED`.
Activation also requires a non-empty client ID/secret, authenticated Redis,
merchant UAT and public HTTPS.

1. Run `./gradlew test`; callback security tests cover hashing, TTLs, invalid
   credentials, expired tokens, atomic duplicate rejection and disabled mode.
2. Confirm Redis requires authentication and is private-network only.
3. Obtain a token, confirm its raw value is absent from `SCAN`, and verify the
   hash-key TTL is positive and no greater than the configured token TTL.
4. Submit the same provider transaction twice. The first may be processed; the
   second must return the idempotent `DUPLICATE` result.

## Failure and rollback

- Redis unavailable: fail authentication/replay checks closed; never mark the
  payment successful.
- Token exposure: rotate the client secret and delete only
  `payment:callback:token:*`; callers must obtain a new token.
- Replay regression/provider anomaly: switch to `GENERATE_ONLY` or `DISABLED`,
  retain PostgreSQL transactions for reconciliation and restart Payment.
- Redis data loss: keep callbacks disabled until reconciliation establishes a
  safe replay window. PostgreSQL remains authoritative.

Never run `FLUSHALL` in shared environments. Rollback changes configuration and
the service image; it does not delete or reverse PostgreSQL migrations.
