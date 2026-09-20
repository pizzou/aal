-- Idempotent production repair for authentication OTP state.
-- Safe when V45 already exists and safe when an older production database
-- missed one or more OTP columns.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS login_otp_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS login_otp_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS login_otp_sent_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS login_otp_attempts INTEGER NOT NULL DEFAULT 0;

UPDATE users
SET login_otp_attempts = 0
WHERE login_otp_attempts IS NULL;

ALTER TABLE users
    ALTER COLUMN login_otp_attempts SET DEFAULT 0,
    ALTER COLUMN login_otp_attempts SET NOT NULL;
