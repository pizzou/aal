-- AAL canonical workbook migration ledger.
-- Excel is a migration source only; Sales & Quotations, Billing & Receivables,
-- and Shipment Register remain the single operational source of truth.
CREATE TABLE IF NOT EXISTS aal_import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    shipments INTEGER NOT NULL DEFAULT 0,
    quotations INTEGER NOT NULL DEFAULT 0,
    invoices INTEGER NOT NULL DEFAULT 0,
    clients INTEGER NOT NULL DEFAULT 0,
    partners INTEGER NOT NULL DEFAULT 0,
    tasks INTEGER NOT NULL DEFAULT 0,
    expenses INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_aal_import_batch_hash UNIQUE (tenant_id, source_sha256)
);

CREATE INDEX IF NOT EXISTS ix_aal_import_batches_tenant_started
    ON aal_import_batches (tenant_id, started_at DESC);

ALTER TABLE aal_import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE aal_import_batches FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS aal_import_batches_tenant_policy ON aal_import_batches;
CREATE POLICY aal_import_batches_tenant_policy ON aal_import_batches
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
