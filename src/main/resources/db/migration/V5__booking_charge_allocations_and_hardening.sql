SET search_path TO app, extensions, public;

-- 1. Fail-safe integrity check: Never synthesize fake Finance references.
-- If legacy records exist without verified Finance Payment Request ID, abort migration.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM booking_charges WHERE finance_payment_request_id IS NULL) THEN
        RAISE EXCEPTION 'Flyway V5 Migration Halted: Found booking_charges with NULL finance_payment_request_id. Manual reconciliation with service_finance is required before applying NOT NULL constraint.';
    END IF;
END $$;

-- 2. Hardening on booking_charges table
ALTER TABLE booking_charges
    ALTER COLUMN finance_payment_request_id SET NOT NULL;

ALTER TABLE booking_charges
    ADD COLUMN IF NOT EXISTS branch_id uuid;

DROP INDEX IF EXISTS ix_booking_charges_finance_request;

CREATE UNIQUE INDEX IF NOT EXISTS uq_booking_charges_finance_request
    ON booking_charges(finance_payment_request_id);

CREATE INDEX IF NOT EXISTS ix_booking_charges_branch_id
    ON booking_charges(branch_id);

-- 3. Allocation Projection (Collection Allocation History for payment tracking & deduplication)
CREATE TABLE IF NOT EXISTS booking_charge_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_id uuid NOT NULL REFERENCES booking_charges(id) ON DELETE RESTRICT,
    finance_allocation_id uuid NOT NULL UNIQUE,
    payment_transaction_id uuid NOT NULL,
    allocated_amount numeric(19,4) NOT NULL,
    currency char(3) NOT NULL DEFAULT 'VND',
    allocated_by_user_id uuid,
    note varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_allocation_amount CHECK (allocated_amount > 0)
);

CREATE INDEX IF NOT EXISTS ix_charge_allocations_charge_id ON booking_charge_allocations(charge_id);
CREATE INDEX IF NOT EXISTS ix_charge_allocations_transaction_id ON booking_charge_allocations(payment_transaction_id);

-- 4. Audit ledger for adjustments (waivers, voids and cancellations)
CREATE TABLE IF NOT EXISTS booking_charge_adjustments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_id uuid NOT NULL REFERENCES booking_charges(id) ON DELETE RESTRICT,
    adjustment_type varchar(32) NOT NULL,
    amount numeric(19,4) NOT NULL,
    reason varchar(500) NOT NULL,
    actor_user_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_adjustment_type CHECK (adjustment_type IN ('WAIVER', 'CANCELLATION')),
    CONSTRAINT ck_adjustment_amount CHECK (amount > 0)
);

CREATE INDEX IF NOT EXISTS ix_charge_adjustments_charge_id ON booking_charge_adjustments(charge_id);
