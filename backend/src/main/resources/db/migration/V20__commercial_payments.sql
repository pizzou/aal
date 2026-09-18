CREATE TABLE IF NOT EXISTS commercial_payments (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 invoice_id UUID NOT NULL REFERENCES commercial_invoices(id),
 amount NUMERIC(19,4) NOT NULL CHECK(amount > 0),
 currency VARCHAR(10) NOT NULL,
 idempotency_key VARCHAR(255) NOT NULL,
 reference VARCHAR(255),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,idempotency_key)
);

ALTER TABLE commercial_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE commercial_payments FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_commercial_payments ON commercial_payments;
CREATE POLICY tenant_isolation_commercial_payments ON commercial_payments
 USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE INDEX IF NOT EXISTS idx_commercial_payments_tenant_invoice ON commercial_payments(tenant_id,invoice_id,created_at);
