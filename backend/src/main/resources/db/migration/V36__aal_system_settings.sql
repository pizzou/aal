CREATE TABLE IF NOT EXISTS aal_system_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    setting_group VARCHAR(80) NOT NULL,
    setting_key VARCHAR(120) NOT NULL,
    setting_value TEXT,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, setting_group, setting_key)
);

CREATE INDEX IF NOT EXISTS ix_aal_settings_tenant_group
    ON aal_system_settings(tenant_id, setting_group);

ALTER TABLE aal_system_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE aal_system_settings FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS aal_system_settings_tenant_policy ON aal_system_settings;
CREATE POLICY aal_system_settings_tenant_policy ON aal_system_settings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
