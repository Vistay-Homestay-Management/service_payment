SET search_path TO app, extensions, public;

CREATE TABLE vietqr_activation_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code varchar(60) NOT NULL UNIQUE,
    merchant_code varchar(60) NOT NULL,
    merchant_name varchar(160) NOT NULL,
    bank_bin varchar(10) NOT NULL,
    bank_name varchar(100) NOT NULL,
    bank_account_number varchar(40) NOT NULL,
    bank_account_name varchar(160) NOT NULL,
    target_activation_mode varchar(24) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    uat_verified boolean NOT NULL DEFAULT false,
    uat_test_reference varchar(180),
    uat_verified_at timestamptz,
    uat_notes text,
    callback_url varchar(500),
    callback_secret_configured boolean NOT NULL DEFAULT false,
    created_by_admin_user_id varchar(100) NOT NULL,
    submitted_at timestamptz,
    approved_by_admin_user_id varchar(100),
    approved_at timestamptz,
    rejection_reason varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_activation_plan_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_activation_target_mode CHECK (target_activation_mode IN ('DISABLED', 'GENERATE_ONLY', 'CALLBACK_ENABLED')),
    CONSTRAINT ck_activation_maker_checker CHECK (approved_by_admin_user_id IS NULL OR approved_by_admin_user_id <> created_by_admin_user_id),
    CONSTRAINT ck_callback_uat_ready CHECK (target_activation_mode <> 'CALLBACK_ENABLED' OR status NOT IN ('APPROVED', 'ACTIVE') OR (uat_verified = true AND callback_secret_configured = true AND callback_url LIKE 'https://%'))
);

CREATE INDEX ix_activation_plan_merchant ON vietqr_activation_plans(merchant_code, status);
CREATE INDEX ix_activation_plan_status ON vietqr_activation_plans(status);

