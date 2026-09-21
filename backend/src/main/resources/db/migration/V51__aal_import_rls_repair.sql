-- Repair the Excel migration ledger policy to use the same tenant setting as the rest of AAL.
-- V48 used app.tenant_id while the canonical TenantAwareDataSource sets app.current_tenant.
ALTER TABLE aal_import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE aal_import_batches FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS aal_import_batches_tenant_policy ON aal_import_batches;
CREATE POLICY aal_import_batches_tenant_policy ON aal_import_batches
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
