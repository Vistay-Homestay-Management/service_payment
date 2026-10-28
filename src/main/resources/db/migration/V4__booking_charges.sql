SET search_path TO app, extensions, public;

CREATE TABLE booking_charges (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_code varchar(60) NOT NULL UNIQUE,
    booking_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    charge_type varchar(32) NOT NULL,
    original_amount numeric(19,4) NOT NULL,
    paid_amount numeric(19,4) NOT NULL DEFAULT 0,
    waived_amount numeric(19,4) NOT NULL DEFAULT 0,
    outstanding_balance numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    due_date timestamptz NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'OPEN',
    description varchar(500),
    cancellation_reason varchar(500),
    last_payment_transaction_id uuid,
    created_by_admin_user_id uuid,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_charge_type CHECK (charge_type IN ('DEPOSIT', 'FULL_PAYMENT', 'DAMAGE_DEPOSIT', 'EXTRA_SERVICE', 'PENALTY')),
    CONSTRAINT ck_charge_status CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID', 'CANCELLED', 'EXPIRED', 'WAIVED')),
    CONSTRAINT ck_charge_amounts CHECK (
        original_amount > 0 
        AND paid_amount >= 0 
        AND waived_amount >= 0 
        AND outstanding_balance >= 0 
        AND (paid_amount + waived_amount + outstanding_balance) = original_amount
    ),
    CONSTRAINT ck_charge_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_booking_charges_booking ON booking_charges(booking_id, status);
CREATE INDEX ix_booking_charges_customer ON booking_charges(customer_id);
CREATE INDEX ix_booking_charges_open_balance ON booking_charges(status, due_date) WHERE status IN ('OPEN', 'PARTIALLY_PAID');
