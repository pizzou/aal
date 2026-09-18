-- AAL Command Center + enterprise air-cargo capabilities.
-- All money is NUMERIC; tenant_id is mandatory and every new table is RLS protected.

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS client_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS contact VARCHAR(255),
    ADD COLUMN IF NOT EXISTS commodity TEXT,
    ADD COLUMN IF NOT EXISTS origin_country VARCHAR(120),
    ADD COLUMN IF NOT EXISTS origin_city_port VARCHAR(255),
    ADD COLUMN IF NOT EXISTS destination_country VARCHAR(120),
    ADD COLUMN IF NOT EXISTS destination_city_port VARCHAR(255),
    ADD COLUMN IF NOT EXISTS gross_weight_kg NUMERIC(18,3),
    ADD COLUMN IF NOT EXISTS volumetric_weight_kg NUMERIC(18,3),
    ADD COLUMN IF NOT EXISTS chargeable_weight_kg NUMERIC(18,3),
    ADD COLUMN IF NOT EXISTS packages INTEGER,
    ADD COLUMN IF NOT EXISTS operator_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS service_type VARCHAR(120),
    ADD COLUMN IF NOT EXISTS etd TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS eta TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supplier_cost NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS other_cost NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS client_revenue NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS amount_paid_by_client NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS amount_paid_to_supply NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS other_expenses NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS owner_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS invoice_no VARCHAR(120),
    ADD COLUMN IF NOT EXISTS next_action TEXT,
    ADD COLUMN IF NOT EXISTS next_action_date DATE,
    ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS currency VARCHAR(10),
    ADD COLUMN IF NOT EXISTS airline_used VARCHAR(255),
    ADD COLUMN IF NOT EXISTS amount_billed_to_client NUMERIC(19,4);

CREATE TABLE IF NOT EXISTS air_cargo_flights (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 carrier_code VARCHAR(40) NOT NULL, carrier_name VARCHAR(255), flight_number VARCHAR(80) NOT NULL,
 origin_code VARCHAR(10) NOT NULL, destination_code VARCHAR(10) NOT NULL,
 departure_time TIMESTAMPTZ NOT NULL, arrival_time TIMESTAMPTZ,
 total_capacity_kg NUMERIC(18,3) NOT NULL CHECK(total_capacity_kg >= 0),
 available_capacity_kg NUMERIC(18,3) NOT NULL CHECK(available_capacity_kg >= 0),
 status VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED', source VARCHAR(80) NOT NULL DEFAULT 'EXTERNAL', updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,carrier_code,flight_number,departure_time)
);
CREATE INDEX IF NOT EXISTS idx_air_flights_route ON air_cargo_flights(tenant_id,origin_code,destination_code,departure_time);

CREATE TABLE IF NOT EXISTS air_cargo_bookings (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 carrier_code VARCHAR(40) NOT NULL, carrier_name VARCHAR(255), flight_number VARCHAR(80) NOT NULL, departure_time TIMESTAMPTZ NOT NULL, arrival_time TIMESTAMPTZ,
 origin_code VARCHAR(10) NOT NULL, destination_code VARCHAR(10) NOT NULL, requested_weight_kg NUMERIC(18,3) NOT NULL CHECK(requested_weight_kg > 0),
 confirmed_weight_kg NUMERIC(18,3), status VARCHAR(40) NOT NULL, provider VARCHAR(100), provider_reference VARCHAR(255), confirmation_number VARCHAR(255),
 idempotency_key VARCHAR(255) NOT NULL, raw_response TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,provider,provider_reference), UNIQUE(tenant_id,idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_air_bookings_shipment ON air_cargo_bookings(tenant_id,shipment_id);

CREATE TABLE IF NOT EXISTS transport_legs (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 sequence_no INTEGER NOT NULL CHECK(sequence_no > 0), mode VARCHAR(20) NOT NULL, origin VARCHAR(255) NOT NULL, destination VARCHAR(255) NOT NULL,
 carrier_name VARCHAR(255), carrier_reference VARCHAR(255), planned_departure TIMESTAMPTZ, planned_arrival TIMESTAMPTZ,
 actual_departure TIMESTAMPTZ, actual_arrival TIMESTAMPTZ, status VARCHAR(40) NOT NULL DEFAULT 'PLANNED',
 UNIQUE(tenant_id,shipment_id,sequence_no)
);

CREATE TABLE IF NOT EXISTS awb_records (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 awb_number VARCHAR(100) NOT NULL, awb_type VARCHAR(20) NOT NULL, mawb_number VARCHAR(100), hawb_number VARCHAR(100),
 shipper_name VARCHAR(255), shipper_address TEXT, consignee_name VARCHAR(255), consignee_address TEXT, issuing_agent VARCHAR(255),
 origin_airport VARCHAR(10), destination_airport VARCHAR(10), pieces INTEGER, gross_weight_kg NUMERIC(18,3), chargeable_weight_kg NUMERIC(18,3),
 commodity TEXT, hs_code VARCHAR(50), special_handling TEXT, dangerous_goods BOOLEAN NOT NULL DEFAULT false,
 validation_status VARCHAR(30) NOT NULL DEFAULT 'PENDING', submission_status VARCHAR(40) NOT NULL DEFAULT 'NOT_SUBMITTED',
 carrier_reference VARCHAR(255), document_uri TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,awb_number)
);

CREATE TABLE IF NOT EXISTS customs_declarations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 declaration_type VARCHAR(50) NOT NULL, customs_authority VARCHAR(255), broker_name VARCHAR(255), hs_codes TEXT, country_of_origin VARCHAR(120),
 declared_value NUMERIC(19,4), currency VARCHAR(10), status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', external_reference VARCHAR(255),
 submission_message TEXT, submitted_at TIMESTAMPTZ,
 UNIQUE(tenant_id,shipment_id,declaration_type)
);

CREATE TABLE IF NOT EXISTS cargo_documents (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 document_type VARCHAR(80) NOT NULL, template_code VARCHAR(100) NOT NULL, file_uri TEXT, content_hash VARCHAR(128),
 status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS commercial_quotes (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), quote_id VARCHAR(100) NOT NULL, quote_date DATE NOT NULL,
 client VARCHAR(255), route VARCHAR(500), service_type VARCHAR(120), commodity TEXT, chargeable_weight_kg NUMERIC(18,3),
 supplier_cost NUMERIC(19,4), other_cost NUMERIC(19,4), markup_percent NUMERIC(9,4), quoted_amount NUMERIC(19,4), expected_profit NUMERIC(19,4),
 valid_until DATE, status VARCHAR(50), owner VARCHAR(255), follow_up_date DATE, notes TEXT, pricing_mode VARCHAR(60), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,quote_id)
);
CREATE TABLE IF NOT EXISTS commercial_invoices (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), invoice_no VARCHAR(100) NOT NULL, issue_date DATE NOT NULL,
 client VARCHAR(255), shipment_id UUID REFERENCES shipments(id), currency VARCHAR(10) NOT NULL, invoice_amount NUMERIC(19,4) NOT NULL,
 amount_paid NUMERIC(19,4) NOT NULL DEFAULT 0, due_date DATE, last_follow_up DATE, next_follow_up DATE, owner VARCHAR(255), notes TEXT,
 UNIQUE(tenant_id,invoice_no)
);
CREATE TABLE IF NOT EXISTS client_records (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), client_id VARCHAR(100) NOT NULL, client_company VARCHAR(255),
 contact_person VARCHAR(255), phone VARCHAR(80), email VARCHAR(255), industry VARCHAR(120), country VARCHAR(120), city VARCHAR(120), lead_source VARCHAR(120),
 client_status VARCHAR(50), relationship_owner VARCHAR(255), next_follow_up DATE, notes TEXT, UNIQUE(tenant_id,client_id)
);
CREATE TABLE IF NOT EXISTS partner_records (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), partner_id VARCHAR(100) NOT NULL, country VARCHAR(120),
 company VARCHAR(255), contact_person VARCHAR(255), phone VARCHAR(80), email VARCHAR(255), services TEXT, city_port_airport VARCHAR(255),
 payment_terms VARCHAR(255), rating INTEGER CHECK(rating BETWEEN 1 AND 5), status VARCHAR(50), last_verified DATE, notes TEXT, UNIQUE(tenant_id,partner_id)
);
CREATE TABLE IF NOT EXISTS task_records (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), task_id VARCHAR(100) NOT NULL, created_date DATE NOT NULL,
 department VARCHAR(120), related_reference VARCHAR(255), task TEXT NOT NULL, priority VARCHAR(50), owner VARCHAR(255), due_date DATE, status VARCHAR(50),
 completion_date DATE, notes TEXT, UNIQUE(tenant_id,task_id)
);
CREATE TABLE IF NOT EXISTS expense_records (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), expense_id VARCHAR(100) NOT NULL, expense_date DATE NOT NULL,
 type VARCHAR(80), category VARCHAR(120), shipment_id UUID REFERENCES shipments(id), client VARCHAR(255), vendor_payee VARCHAR(255), description TEXT,
 currency VARCHAR(10) NOT NULL, original_amount NUMERIC(19,4) NOT NULL, exchange_rate_to_usd NUMERIC(19,8), usd_equivalent NUMERIC(19,4),
 payment_method VARCHAR(80), status VARCHAR(50), approved_by VARCHAR(255), UNIQUE(tenant_id,expense_id)
);
CREATE TABLE IF NOT EXISTS iot_devices (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), device_code VARCHAR(120) NOT NULL, shipment_id UUID REFERENCES shipments(id),
 secret_hash VARCHAR(128) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true, last_seen_at TIMESTAMPTZ, UNIQUE(tenant_id,device_code)
);

DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['air_cargo_flights','air_cargo_bookings','transport_legs','awb_records','customs_declarations','cargo_documents','commercial_quotes','commercial_invoices','client_records','partner_records','task_records','expense_records','iot_devices']
 LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
  EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
 END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS finance_ledger_entries (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), invoice_id UUID REFERENCES commercial_invoices(id),
 entry_type VARCHAR(20) NOT NULL, account_code VARCHAR(100) NOT NULL, amount NUMERIC(19,4) NOT NULL CHECK(amount >= 0),
 currency VARCHAR(10) NOT NULL, description TEXT, posted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE finance_ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_ledger_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_finance_ledger_entries ON finance_ledger_entries
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE TABLE IF NOT EXISTS document_templates (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), template_code VARCHAR(100) NOT NULL,
 name VARCHAR(255) NOT NULL, version VARCHAR(30) NOT NULL, required_fields TEXT, active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,template_code)
);
ALTER TABLE document_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_document_templates ON document_templates
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE TABLE IF NOT EXISTS cargo_pieces (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 piece_no INTEGER NOT NULL, length_cm NUMERIC(12,3) NOT NULL, width_cm NUMERIC(12,3) NOT NULL, height_cm NUMERIC(12,3) NOT NULL, weight_kg NUMERIC(12,3) NOT NULL,
 stackable BOOLEAN NOT NULL DEFAULT true, temperature_controlled BOOLEAN NOT NULL DEFAULT false,
 UNIQUE(tenant_id,shipment_id,piece_no)
);
ALTER TABLE cargo_pieces ENABLE ROW LEVEL SECURITY;
ALTER TABLE cargo_pieces FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_cargo_pieces ON cargo_pieces
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
