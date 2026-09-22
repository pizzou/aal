-- Security key rotation metadata. Secrets themselves remain environment-managed.
CREATE TABLE IF NOT EXISTS security_key_rotations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID REFERENCES tenants(id),
  key_type VARCHAR(40) NOT NULL,
  key_version VARCHAR(80) NOT NULL,
  activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  retired_at TIMESTAMPTZ,
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(key_type,key_version)
);
CREATE INDEX IF NOT EXISTS ix_security_key_rotations_active
  ON security_key_rotations(key_type,status,activated_at DESC);
