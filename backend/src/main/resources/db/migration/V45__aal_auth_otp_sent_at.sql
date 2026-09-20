-- AAL authentication hardening.
-- The OTP service uses login_otp_sent_at for resend throttling.  Keep this
-- migration additive so existing production databases can be upgraded safely.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS login_otp_sent_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_login_otp_sent_at
    ON users(tenant_id, login_otp_sent_at)
    WHERE login_otp_sent_at IS NOT NULL;
