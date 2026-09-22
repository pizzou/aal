-- Effective-dated pricing: multiple historical/future versions per lane/mode.
ALTER TABLE rate_cards
  ADD COLUMN IF NOT EXISTS security_surcharge_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS markup_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS lane_code VARCHAR(120),
  ADD COLUMN IF NOT EXISTS client_id UUID REFERENCES client_records(id),
  ADD COLUMN IF NOT EXISTS carrier_id UUID REFERENCES carrier_master(id),
  ADD COLUMN IF NOT EXISTS valid_from DATE NOT NULL DEFAULT CURRENT_DATE,
  ADD COLUMN IF NOT EXISTS valid_until DATE,
  ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid = 'rate_cards'::regclass
      AND conname = 'rate_cards_tenant_id_transport_mode_key'
  ) THEN
    ALTER TABLE rate_cards DROP CONSTRAINT rate_cards_tenant_id_transport_mode_key;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_rate_cards_effective_match
  ON rate_cards(tenant_id,transport_mode,lane_code,client_id,carrier_id,active,valid_from DESC);

CREATE TABLE IF NOT EXISTS pricing_discounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  discount_code VARCHAR(100) NOT NULL,
  client_id UUID REFERENCES client_records(id),
  mode VARCHAR(40),
  lane_code VARCHAR(120),
  min_charge NUMERIC(19,4),
  discount_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  valid_from DATE NOT NULL,
  valid_until DATE,
  priority INTEGER NOT NULL DEFAULT 100,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE(tenant_id,discount_code)
);
CREATE INDEX IF NOT EXISTS ix_pricing_discounts_match
  ON pricing_discounts(tenant_id,client_id,mode,lane_code,active,priority,valid_from DESC);

ALTER TABLE customer_rate_cards
  ADD COLUMN IF NOT EXISTS markup_percent NUMERIC(9,4) NOT NULL DEFAULT 0;

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['pricing_discounts'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
