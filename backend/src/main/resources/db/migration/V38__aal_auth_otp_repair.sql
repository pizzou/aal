ALTER TABLE users
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone VARCHAR(50);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS login_otp_hash VARCHAR(255);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS login_otp_expires_at TIMESTAMPTZ;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS login_otp_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS customer_client_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_users_tenant_email
    ON users(tenant_id, email);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user
    ON password_reset_tokens(tenant_id, user_id);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_lookup
    ON password_reset_tokens(tenant_id, token_hash, used);

CREATE TABLE IF NOT EXISTS user_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(60) NOT NULL DEFAULT 'GENERAL',
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    link VARCHAR(1000),
    read BOOLEAN NOT NULL DEFAULT false,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user
    ON user_notifications(tenant_id, user_id, created_at DESC);