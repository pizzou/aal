-- AAL Advanced Logistics Completion
-- This migration completes the professional operating layer without creating
-- duplicate operational stores. Shipment, Sales & Quotations, and Billing &
-- Receivables remain canonical.

ALTER TABLE commercial_invoices
  ADD COLUMN IF NOT EXISTS tax_rate NUMERIC(9,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS tax_code VARCHAR(40),
  ADD COLUMN IF NOT EXISTS withholding_amount NUMERIC(19,4) NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_invoice_tax ON commercial_invoices(tenant_id,tax_code,issue_date);
ALTER TABLE pricing_rules
  ADD COLUMN IF NOT EXISTS client_id UUID REFERENCES client_records(id),
  ADD COLUMN IF NOT EXISTS carrier_id UUID REFERENCES carrier_master(id),
  ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 100;
CREATE INDEX IF NOT EXISTS ix_pricing_rules_customer_lane
  ON pricing_rules(tenant_id,client_id,carrier_id,lane_code,mode,active,priority);

ALTER TABLE rate_cards
  ADD COLUMN IF NOT EXISTS lane_code VARCHAR(120),
  ADD COLUMN IF NOT EXISTS client_id UUID REFERENCES client_records(id),
  ADD COLUMN IF NOT EXISTS carrier_id UUID REFERENCES carrier_master(id),
  ADD COLUMN IF NOT EXISTS valid_from DATE NOT NULL DEFAULT CURRENT_DATE,
  ADD COLUMN IF NOT EXISTS valid_until DATE,
  ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX IF NOT EXISTS ix_rate_cards_match
  ON rate_cards(tenant_id,client_id,carrier_id,transport_mode,lane_code,active,valid_from);

CREATE TABLE IF NOT EXISTS customer_rate_cards (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  client_id UUID NOT NULL REFERENCES client_records(id),
  card_code VARCHAR(100) NOT NULL,
  mode VARCHAR(40) NOT NULL,
  lane_code VARCHAR(120),
  base_rate_per_kg NUMERIC(19,6) NOT NULL DEFAULT 0,
  min_charge NUMERIC(19,4) NOT NULL DEFAULT 0,
  fuel_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  security_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  valid_from DATE NOT NULL,
  valid_until DATE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  terms TEXT,
  UNIQUE(tenant_id,card_code)
);
CREATE INDEX IF NOT EXISTS ix_customer_rate_cards_match
 ON customer_rate_cards(tenant_id,client_id,mode,lane_code,active,valid_from);

CREATE TABLE IF NOT EXISTS carrier_buy_rates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  carrier_id UUID REFERENCES carrier_master(id),
  carrier_name VARCHAR(255) NOT NULL,
  mode VARCHAR(40) NOT NULL,
  lane_code VARCHAR(120),
  base_rate_per_kg NUMERIC(19,6) NOT NULL DEFAULT 0,
  min_charge NUMERIC(19,4) NOT NULL DEFAULT 0,
  fuel_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  security_percent NUMERIC(9,4) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  valid_from DATE NOT NULL,
  valid_until DATE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  contract_reference VARCHAR(160),
  UNIQUE(tenant_id,carrier_name,mode,lane_code,valid_from)
);
CREATE INDEX IF NOT EXISTS ix_carrier_buy_rates_match
 ON carrier_buy_rates(tenant_id,mode,lane_code,active,valid_from);

CREATE TABLE IF NOT EXISTS commercial_quote_charges (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  quote_id UUID NOT NULL REFERENCES commercial_quotes(id) ON DELETE CASCADE,
  code VARCHAR(80) NOT NULL,
  category VARCHAR(60) NOT NULL,
  description VARCHAR(255) NOT NULL,
  buy_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  sell_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  quantity NUMERIC(19,4) NOT NULL DEFAULT 1,
  unit_rate NUMERIC(19,6),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_quote_charges_quote
 ON commercial_quote_charges(tenant_id,quote_id);

CREATE TABLE IF NOT EXISTS ocean_free_time_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  carrier_name VARCHAR(255),
  port_code VARCHAR(20),
  container_type VARCHAR(40),
  free_days INTEGER NOT NULL DEFAULT 0,
  demurrage_per_day NUMERIC(19,4) NOT NULL DEFAULT 0,
  detention_per_day NUMERIC(19,4) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  valid_from DATE NOT NULL,
  valid_until DATE,
  active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS ix_ocean_free_time_match
 ON ocean_free_time_rules(tenant_id,carrier_name,port_code,container_type,active,valid_from);

CREATE TABLE IF NOT EXISTS ocean_charge_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID NOT NULL REFERENCES shipments(id),
  container_id UUID REFERENCES ocean_containers(id),
  charge_type VARCHAR(30) NOT NULL,
  event_date DATE NOT NULL,
  free_days INTEGER NOT NULL DEFAULT 0,
  billable_days INTEGER NOT NULL DEFAULT 0,
  daily_rate NUMERIC(19,4) NOT NULL DEFAULT 0,
  amount NUMERIC(19,4) NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'USD',
  status VARCHAR(30) NOT NULL DEFAULT 'CALCULATED',
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_ocean_charges_shipment
 ON ocean_charge_events(tenant_id,shipment_id,event_date);

CREATE TABLE IF NOT EXISTS warehouse_barcodes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID REFERENCES shipments(id),
  inventory_item_id UUID REFERENCES inventory_items(id),
  barcode VARCHAR(160) NOT NULL,
  barcode_type VARCHAR(30) NOT NULL DEFAULT 'CODE128',
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,barcode)
);

CREATE TABLE IF NOT EXISTS warehouse_cycle_counts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  warehouse_id UUID NOT NULL REFERENCES warehouses(id),
  location_code VARCHAR(160),
  scheduled_at TIMESTAMPTZ NOT NULL,
  counted_at TIMESTAMPTZ,
  status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  expected_quantity NUMERIC(19,3) NOT NULL DEFAULT 0,
  counted_quantity NUMERIC(19,3),
  variance NUMERIC(19,3),
  counted_by VARCHAR(255),
  notes TEXT
);
CREATE INDEX IF NOT EXISTS ix_cycle_counts_queue
 ON warehouse_cycle_counts(tenant_id,warehouse_id,status,scheduled_at);

CREATE TABLE IF NOT EXISTS customs_declaration_lines (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  declaration_id UUID NOT NULL REFERENCES customs_declarations(id) ON DELETE CASCADE,
  line_no INTEGER NOT NULL,
  hs_code VARCHAR(20) NOT NULL,
  description VARCHAR(500),
  country_of_origin VARCHAR(3),
  quantity NUMERIC(19,3),
  unit_value NUMERIC(19,4),
  declared_value NUMERIC(19,4),
  currency VARCHAR(3),
  duty_rate NUMERIC(9,4),
  tax_rate NUMERIC(9,4),
  UNIQUE(tenant_id,declaration_id,line_no)
);

CREATE TABLE IF NOT EXISTS finance_supplier_bills (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  supplier_name VARCHAR(255) NOT NULL,
  supplier_invoice_no VARCHAR(160) NOT NULL,
  shipment_id UUID REFERENCES shipments(id),
  currency VARCHAR(3) NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK(amount >= 0),
  amount_paid NUMERIC(19,4) NOT NULL DEFAULT 0,
  issue_date DATE NOT NULL,
  due_date DATE,
  status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  notes TEXT,
  UNIQUE(tenant_id,supplier_name,supplier_invoice_no)
);
CREATE INDEX IF NOT EXISTS ix_supplier_bills_due
 ON finance_supplier_bills(tenant_id,due_date,status);

CREATE TABLE IF NOT EXISTS finance_bank_transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  bank_account VARCHAR(160) NOT NULL,
  transaction_date DATE NOT NULL,
  reference VARCHAR(255),
  amount NUMERIC(19,4) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  direction VARCHAR(20) NOT NULL,
  matched_invoice_id UUID REFERENCES commercial_invoices(id),
  matched_payment_id UUID REFERENCES commercial_payments(id),
  status VARCHAR(30) NOT NULL DEFAULT 'UNMATCHED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_bank_transactions_match
 ON finance_bank_transactions(tenant_id,bank_account,transaction_date,status);

CREATE TABLE IF NOT EXISTS finance_reconciliation_matches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  bank_transaction_id UUID NOT NULL REFERENCES finance_bank_transactions(id) ON DELETE CASCADE,
  invoice_id UUID REFERENCES commercial_invoices(id),
  payment_id UUID REFERENCES commercial_payments(id),
  matched_amount NUMERIC(19,4) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'MATCHED',
  matched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,bank_transaction_id)
);

CREATE TABLE IF NOT EXISTS carrier_performance_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  carrier_id UUID REFERENCES carrier_master(id),
  carrier_name VARCHAR(255) NOT NULL,
  shipment_id UUID REFERENCES shipments(id),
  event_type VARCHAR(60) NOT NULL,
  planned_at TIMESTAMPTZ,
  actual_at TIMESTAMPTZ,
  variance_minutes INTEGER,
  score NUMERIC(9,4),
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_carrier_perf
 ON carrier_performance_events(tenant_id,carrier_name,event_type,created_at DESC);

CREATE TABLE IF NOT EXISTS shipment_readiness_checks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  check_code VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  message TEXT NOT NULL,
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,shipment_id,check_code)
);

CREATE TABLE IF NOT EXISTS workflow_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  rule_code VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  condition_json TEXT NOT NULL,
  action_json TEXT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,rule_code)
);

CREATE TABLE IF NOT EXISTS workflow_runs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  rule_code VARCHAR(100) NOT NULL,
  entity_type VARCHAR(80) NOT NULL,
  entity_id UUID,
  status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED',
  result_json TEXT,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_workflow_runs
 ON workflow_runs(tenant_id,rule_code,started_at DESC);

CREATE TABLE IF NOT EXISTS document_signature_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  document_id UUID NOT NULL REFERENCES cargo_documents(id) ON DELETE CASCADE,
  signer_name VARCHAR(255) NOT NULL,
  signer_email VARCHAR(255),
  status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
  signature_reference VARCHAR(255),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  signed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS customer_feedback (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  shipment_id UUID REFERENCES shipments(id),
  quote_id UUID REFERENCES commercial_quotes(id),
  rating INTEGER CHECK(rating BETWEEN 1 AND 5),
  category VARCHAR(80),
  comment TEXT,
  contact_email VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS api_webhooks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  event_type VARCHAR(120) NOT NULL,
  endpoint_url TEXT NOT NULL,
  secret_hash VARCHAR(128),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,event_type,endpoint_url)
);

CREATE TABLE IF NOT EXISTS mobile_sync_queue (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  device_id VARCHAR(160) NOT NULL,
  operation_id VARCHAR(160) NOT NULL,
  entity_type VARCHAR(80) NOT NULL,
  entity_id UUID,
  payload_json TEXT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  UNIQUE(tenant_id,device_id,operation_id)
);

DO $$ DECLARE t text; BEGIN
  FOR t IN SELECT unnest(ARRAY[
    'customer_rate_cards','carrier_buy_rates','commercial_quote_charges',
    'ocean_free_time_rules','ocean_charge_events','warehouse_barcodes',
    'warehouse_cycle_counts','customs_declaration_lines','finance_supplier_bills',
    'finance_bank_transactions','finance_reconciliation_matches','carrier_performance_events',
    'shipment_readiness_checks','workflow_rules','workflow_runs',
    'document_signature_requests','customer_feedback','api_webhooks','mobile_sync_queue'
  ]) LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',
      t,t
    );
  END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS ix_shipments_advanced_eta
 ON shipments(tenant_id,eta,status);
CREATE INDEX IF NOT EXISTS ix_shipments_advanced_mode
 ON shipments(tenant_id,transport_mode,status);
CREATE INDEX IF NOT EXISTS ix_transport_leg_eta
 ON transport_legs(tenant_id,shipment_id,planned_arrival,status);
CREATE INDEX IF NOT EXISTS ix_customs_lines_hs
 ON customs_declaration_lines(tenant_id,hs_code);


-- Provider-neutral integration registry. These are intentionally disabled until
-- AAL supplies real credentials, endpoints and completes provider UAT.
INSERT INTO integration_registry(tenant_id,code,display_name,protocol,enabled,health_status)
SELECT t.id, x.code, x.display_name, x.protocol, false, 'NOT_CONFIGURED'
FROM tenants t
CROSS JOIN (VALUES
 ('IATA_ONE_RECORD','IATA ONE Record','API'),
 ('DCSA_TRACK_TRACE','DCSA Track & Trace','API'),
 ('DCSA_BOOKING','DCSA Booking','API'),
 ('WCO_CUSTOMS','WCO / Customs Adapter','API'),
 ('FIATA_EFBL','FIATA eFBL','API'),
 ('AIRLINE_EBOOKING','Airline eBooking','API'),
 ('OCEAN_CARRIER','Ocean Carrier','API'),
 ('PORT_EVENTS','Port / Terminal Events','API'),
 ('GPS_PROVIDER','GPS / Telematics','API'),
 ('PAYMENT_GATEWAY','Payment Gateway','API'),
 ('ACCOUNTING','Accounting System','API'),
 ('EDI','EDI Gateway','EDI'),
 ('SMS_WHATSAPP','SMS / WhatsApp','API')
) AS x(code,display_name,protocol)
WHERE NOT EXISTS (
  SELECT 1 FROM integration_registry i
  WHERE i.tenant_id=t.id AND i.code=x.code
);
