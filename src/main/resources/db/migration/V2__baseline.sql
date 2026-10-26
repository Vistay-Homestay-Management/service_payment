SET search_path TO app, extensions, public;

CREATE TABLE payment_intents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_code varchar(40) NOT NULL UNIQUE,
    finance_payment_request_id uuid NOT NULL,
    booking_id uuid,
    customer_id uuid,
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    purpose_snapshot varchar(500) NOT NULL,
    payer_reference varchar(160),
    provider varchar(40) NOT NULL DEFAULT 'VIETQR',
    provider_order_id varchar(13) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'CREATED',
    activation_mode varchar(24) NOT NULL,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    succeeded_at timestamptz,
    cancelled_at timestamptz,
    cancellation_reason varchar(500),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_intent_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_intent_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_intent_status CHECK (status IN ('CREATED', 'QR_READY', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_payment_intent_mode CHECK (activation_mode IN ('DISABLED', 'GENERATE_ONLY', 'CALLBACK_ENABLED')),
    CONSTRAINT ck_payment_intent_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_payment_intent_success CHECK ((status = 'SUCCEEDED' AND succeeded_at IS NOT NULL) OR status <> 'SUCCEEDED'),
    CONSTRAINT ck_payment_intent_cancel CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL) OR status <> 'CANCELLED')
);
CREATE INDEX ix_payment_intent_finance_request ON payment_intents(finance_payment_request_id, created_at DESC);
CREATE INDEX ix_payment_intent_booking ON payment_intents(booking_id, created_at DESC) WHERE booking_id IS NOT NULL;
CREATE INDEX ix_payment_intent_pending ON payment_intents(status, expires_at) WHERE status IN ('CREATED', 'QR_READY', 'PENDING');
CREATE UNIQUE INDEX ux_payment_intent_provider_order ON payment_intents(provider, provider_order_id);

CREATE TABLE vietqr_presentations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id uuid NOT NULL REFERENCES payment_intents(id) ON DELETE RESTRICT,
    provider_request_id varchar(180),
    qr_payload text NOT NULL,
    qr_image_data_uri text,
    account_display_masked varchar(120) NOT NULL,
    transfer_content varchar(23) NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    superseded_at timestamptz,
    CONSTRAINT ck_vietqr_expiry CHECK (expires_at > generated_at)
);
CREATE UNIQUE INDEX ux_vietqr_active_presentation
    ON vietqr_presentations(payment_intent_id)
    WHERE superseded_at IS NULL;

CREATE TABLE provider_transactions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(40) NOT NULL,
    reference_number varchar(180) NOT NULL,
    provider_transaction_id varchar(180),
    payment_intent_id uuid REFERENCES payment_intents(id) ON DELETE SET NULL,
    provider_order_id varchar(13),
    amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    direction varchar(12) NOT NULL,
    transfer_content varchar(500),
    account_reference_hash char(64),
    payload_sha256 char(64) NOT NULL,
    match_status varchar(24) NOT NULL,
    mismatch_reason varchar(500),
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    matched_at timestamptz,
    CONSTRAINT ux_provider_transaction_reference UNIQUE (provider, reference_number),
    CONSTRAINT ck_provider_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_provider_transaction_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_provider_transaction_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_provider_transaction_match CHECK (match_status IN ('MATCHED', 'UNMATCHED', 'REJECTED', 'DUPLICATE')),
    CONSTRAINT ck_provider_transaction_hash CHECK (payload_sha256 ~ '^[0-9a-fA-F]{64}$')
);
CREATE UNIQUE INDEX ux_provider_transaction_id
    ON provider_transactions(provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;
CREATE INDEX ix_provider_transaction_intent ON provider_transactions(payment_intent_id, occurred_at DESC) WHERE payment_intent_id IS NOT NULL;
CREATE INDEX ix_provider_transaction_unmatched ON provider_transactions(received_at) WHERE match_status = 'UNMATCHED';

CREATE TABLE webhook_inbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(40) NOT NULL,
    external_reference varchar(180) NOT NULL,
    payload_sha256 char(64) NOT NULL,
    auth_subject varchar(160),
    token_jti varchar(160),
    status varchar(24) NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    processing_error varchar(2000),
    CONSTRAINT ux_webhook_inbox_reference UNIQUE (provider, external_reference),
    CONSTRAINT ck_webhook_inbox_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'REJECTED', 'FAILED', 'QUARANTINED')),
    CONSTRAINT ck_webhook_payload_hash CHECK (payload_sha256 ~ '^[0-9a-fA-F]{64}$')
);

CREATE TABLE reconciliation_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_transaction_id uuid NOT NULL UNIQUE REFERENCES provider_transactions(id) ON DELETE RESTRICT,
    reason_code varchar(80) NOT NULL,
    detail varchar(2000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    resolved_payment_intent_id uuid REFERENCES payment_intents(id) ON DELETE SET NULL,
    resolution varchar(1000),
    resolved_by_admin_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_reconciliation_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED')),
    CONSTRAINT ck_reconciliation_resolution CHECK ((status IN ('RESOLVED', 'REJECTED') AND resolved_at IS NOT NULL AND resolved_by_admin_user_id IS NOT NULL) OR status IN ('OPEN', 'IN_REVIEW'))
);
CREATE INDEX ix_reconciliation_open ON reconciliation_items(status, created_at) WHERE status IN ('OPEN', 'IN_REVIEW');

CREATE TABLE processed_events (
    event_id uuid NOT NULL,
    consumer_name varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_error text,
    correlation_id varchar(100),
    causation_id varchar(100)
);
CREATE INDEX ix_payment_outbox_pending ON outbox_events(available_at, occurred_at) WHERE published_at IS NULL;

CREATE TABLE audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id varchar(160),
    action varchar(100) NOT NULL,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    reason varchar(1000),
    changes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(changes) = 'object'),
    correlation_id varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_audit_aggregate ON audit_events(aggregate_type, aggregate_id, created_at DESC);
