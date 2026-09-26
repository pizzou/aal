CREATE TABLE IF NOT EXISTS integration_job_locks (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    job_name VARCHAR(160) NOT NULL,
    locked_until TIMESTAMPTZ NOT NULL DEFAULT now(),
    owner_id VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,job_name)
);
ALTER TABLE integration_job_locks ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_job_locks FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_integration_job_locks ON integration_job_locks;
CREATE POLICY tenant_isolation_integration_job_locks ON integration_job_locks
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
