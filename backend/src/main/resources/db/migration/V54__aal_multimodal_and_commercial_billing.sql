-- AAL advanced multimodal journey and governed commercial billing.
ALTER TABLE commercial_invoices
  ADD COLUMN IF NOT EXISTS quote_id UUID REFERENCES commercial_quotes(id),
  ADD COLUMN IF NOT EXISTS quote_version_id UUID REFERENCES commercial_quote_versions(id),
  ADD COLUMN IF NOT EXISTS governed_amount NUMERIC(19,4),
  ADD COLUMN IF NOT EXISTS governed_currency VARCHAR(3);

CREATE INDEX IF NOT EXISTS ix_invoices_quote
  ON commercial_invoices(tenant_id,quote_id,quote_version_id);

CREATE TABLE IF NOT EXISTS commercial_invoice_lines (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  invoice_id UUID NOT NULL REFERENCES commercial_invoices(id) ON DELETE CASCADE,
  line_no INTEGER NOT NULL,
  charge_code VARCHAR(80) NOT NULL,
  description VARCHAR(500) NOT NULL,
  quantity NUMERIC(19,4) NOT NULL DEFAULT 1,
  unit_price NUMERIC(19,6) NOT NULL DEFAULT 0,
  amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  source_type VARCHAR(40),
  source_reference VARCHAR(160),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,invoice_id,line_no)
);
CREATE INDEX IF NOT EXISTS ix_invoice_lines_invoice
  ON commercial_invoice_lines(tenant_id,invoice_id,line_no);

CREATE TABLE IF NOT EXISTS shipment_journeys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  journey_reference VARCHAR(120) NOT NULL,
  service_type VARCHAR(60),
  planned_start TIMESTAMPTZ,
  planned_end TIMESTAMPTZ,
  actual_start TIMESTAMPTZ,
  actual_end TIMESTAMPTZ,
  unified_eta TIMESTAMPTZ,
  status VARCHAR(40) NOT NULL DEFAULT 'PLANNED',
  customer_visible BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,shipment_id),
  UNIQUE(tenant_id,journey_reference)
);
CREATE INDEX IF NOT EXISTS ix_shipment_journeys_operational
  ON shipment_journeys(tenant_id,status,unified_eta);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['commercial_invoice_lines','shipment_journeys'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
