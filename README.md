# ViStay Payment Service

Owns payment intents, VietQR generation, provider callback ingestion and reconciliation. Finance remains the owner of receivables, verified accounting transactions, allocation, refunds, payouts and ledger state.

## Activation modes

- `DISABLED`: health/read queries only.
- `GENERATE_ONLY`: generate QR and keep the intent pending for manual Finance verification.
- `CALLBACK_ENABLED`: accept VietQR Transaction Sync after merchant UAT and public HTTPS gates pass.

Creating a QR never marks a booking or receivable as paid.

## Local verification

```powershell
./gradlew.bat test
```

Runtime secrets and provider credentials must be injected outside Git.

The Redis security-state boundary, activation checks and rollback procedure are
versioned in [`docs/redis-security-runbook.md`](docs/redis-security-runbook.md).
