CREATE TABLE IF NOT EXISTS payment_gateway_transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  invoice_id UUID NOT NULL REFERENCES commercial_invoices(id),
  provider VARCHAR(40) NOT NULL,
  provider_order_id VARCHAR(160),
  provider_capture_id VARCHAR(160),
  status VARCHAR(40) NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK(amount > 0),
  currency VARCHAR(3) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  response_json TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id, provider, idempotency_key)
);

CREATE INDEX IF NOT EXISTS ix_payment_gateway_transactions_invoice
  ON payment_gateway_transactions(tenant_id, invoice_id, created_at DESC);

ALTER TABLE payment_gateway_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_gateway_transactions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_payment_gateway_transactions ON payment_gateway_transactions;
CREATE POLICY tenant_isolation_payment_gateway_transactions
  ON payment_gateway_transactions
  USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
