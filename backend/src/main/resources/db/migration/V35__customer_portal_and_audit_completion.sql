ALTER TABLE users ADD COLUMN IF NOT EXISTS customer_client_id VARCHAR(120);
CREATE INDEX IF NOT EXISTS ix_users_customer_client ON users(tenant_id,customer_client_id) WHERE customer_client_id IS NOT NULL;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS before_state TEXT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS after_state TEXT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(120);
CREATE INDEX IF NOT EXISTS ix_audit_correlation ON audit_logs(tenant_id,correlation_id);
