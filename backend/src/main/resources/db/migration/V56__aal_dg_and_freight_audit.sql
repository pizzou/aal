-- Dangerous goods compliance and shipment freight audit.
CREATE TABLE IF NOT EXISTS dangerous_goods_declarations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  cargo_item_id UUID REFERENCES cargo_items(id),
  un_number VARCHAR(16) NOT NULL,
  proper_shipping_name VARCHAR(255) NOT NULL,
  hazard_class VARCHAR(20) NOT NULL,
  packing_group VARCHAR(10),
  quantity NUMERIC(19,4) NOT NULL,
  quantity_unit VARCHAR(30) NOT NULL,
  package_count INTEGER NOT NULL DEFAULT 1,
  tunnel_code VARCHAR(20),
  marine_pollutant BOOLEAN NOT NULL DEFAULT FALSE,
  limited_quantity BOOLEAN NOT NULL DEFAULT FALSE,
  excepted_quantity BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  validated_at TIMESTAMPTZ,
  validated_by UUID REFERENCES users(id),
  validation_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_dg_declarations_shipment
  ON dangerous_goods_declarations(tenant_id,shipment_id,status);

CREATE TABLE IF NOT EXISTS freight_audit_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID NOT NULL REFERENCES shipments(id),
  invoice_id UUID REFERENCES commercial_invoices(id),
  expected_revenue NUMERIC(19,4) NOT NULL DEFAULT 0,
  actual_revenue NUMERIC(19,4) NOT NULL DEFAULT 0,
  supplier_cost NUMERIC(19,4) NOT NULL DEFAULT 0,
  operational_cost NUMERIC(19,4) NOT NULL DEFAULT 0,
  variance NUMERIC(19,4) NOT NULL DEFAULT 0,
  gross_margin NUMERIC(19,4) NOT NULL DEFAULT 0,
  margin_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL,
  findings_json TEXT,
  audited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  audited_by UUID REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS ix_freight_audit_shipment
  ON freight_audit_results(tenant_id,shipment_id,audited_at DESC);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['dangerous_goods_declarations','freight_audit_results'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
