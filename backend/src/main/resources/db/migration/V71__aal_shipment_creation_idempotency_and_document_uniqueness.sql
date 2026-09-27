-- AAL Tier 1: durable shipment-create idempotency and document-number uniqueness.
-- The shipment reference is an internal AAL identifier; real MAWB/HAWB numbers
-- remain in awb_records and must be allocated/validated by the AWB workflow.

CREATE TABLE IF NOT EXISTS shipment_creation_idempotency (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    shipment_id UUID REFERENCES shipments(id),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS ix_shipment_creation_idempotency_shipment
    ON shipment_creation_idempotency(tenant_id, shipment_id);

ALTER TABLE shipment_creation_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_creation_idempotency FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_shipment_creation_idempotency
    ON shipment_creation_idempotency;
CREATE POLICY tenant_isolation_shipment_creation_idempotency
    ON shipment_creation_idempotency
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Imported/legacy null invoice numbers remain allowed. Non-null invoice numbers
-- must be unique per AAL tenant. Existing duplicate data intentionally fails this
-- migration instead of being silently rewritten.
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipments_tenant_invoice_no
    ON shipments(tenant_id, invoice_no)
    WHERE invoice_no IS NOT NULL AND btrim(invoice_no) <> '';
