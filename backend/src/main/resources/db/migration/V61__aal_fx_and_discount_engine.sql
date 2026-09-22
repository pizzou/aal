-- Commercial discount and FX controls used by the canonical rating engine.
ALTER TABLE commercial_quotes
  ADD COLUMN IF NOT EXISTS discount_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS source_currency VARCHAR(3),
  ADD COLUMN IF NOT EXISTS pricing_snapshot_json TEXT;

ALTER TABLE finance_fx_rates
  ADD COLUMN IF NOT EXISTS effective_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_finance_fx_effective
  ON finance_fx_rates(tenant_id,base_currency,quote_currency,rate_date DESC,effective_at DESC);

CREATE TABLE IF NOT EXISTS pricing_margin_controls (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  mode VARCHAR(40),
  client_id UUID REFERENCES client_records(id),
  minimum_margin_percent NUMERIC(9,4) NOT NULL DEFAULT 15,
  hard_block BOOLEAN NOT NULL DEFAULT FALSE,
  valid_from DATE NOT NULL,
  valid_until DATE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE(tenant_id,mode,client_id,valid_from)
);
CREATE INDEX IF NOT EXISTS ix_pricing_margin_controls
  ON pricing_margin_controls(tenant_id,mode,client_id,active,valid_from DESC);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['pricing_margin_controls'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
