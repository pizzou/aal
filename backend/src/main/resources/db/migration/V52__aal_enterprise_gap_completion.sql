-- AAL Enterprise Gap Completion
-- Uses existing canonical Shipment, Sales & Quotations, Billing & Receivables,
-- WMS/TMS, customs, document and integration stores. New tables below exist only
-- where the checklist requires a distinct lifecycle/ledger that is not already present.

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS actual_departure TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS actual_arrival TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS incoterm VARCHAR(20),
    ADD COLUMN IF NOT EXISTS declared_value NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS declared_currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS customer_visible_status VARCHAR(50);

ALTER TABLE awb_records
    ADD COLUMN IF NOT EXISTS cargo_acceptance_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS eawb_status VARCHAR(40) NOT NULL DEFAULT 'NOT_READY',
    ADD COLUMN IF NOT EXISTS one_record_reference VARCHAR(255);

ALTER TABLE ocean_bookings
    ADD COLUMN IF NOT EXISTS shipping_instruction_status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN IF NOT EXISTS hbl_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS mbl_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS bill_status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS ebl_reference VARCHAR(255);

ALTER TABLE road_consignments
    ADD COLUMN IF NOT EXISTS route_plan_json TEXT,
    ADD COLUMN IF NOT EXISTS pickup_actual TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delivery_actual TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS exception_code VARCHAR(80);

ALTER TABLE cargo_documents
    ADD COLUMN IF NOT EXISTS approval_status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scan_status VARCHAR(30) NOT NULL DEFAULT 'NOT_SCANNED';

ALTER TABLE integration_registry
    ADD COLUMN IF NOT EXISTS config_ref VARCHAR(255),
    ADD COLUMN IF NOT EXISTS capabilities_json TEXT;

CREATE TABLE IF NOT EXISTS carrier_contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    carrier_id UUID REFERENCES carrier_master(id),
    contract_no VARCHAR(120) NOT NULL,
    carrier_name VARCHAR(255) NOT NULL,
    mode VARCHAR(40) NOT NULL,
    lane_code VARCHAR(120),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    valid_from DATE NOT NULL,
    valid_until DATE,
    payment_terms VARCHAR(120),
    terms TEXT,
    rate_snapshot_json TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,contract_no)
);
CREATE INDEX IF NOT EXISTS ix_carrier_contracts_lookup
    ON carrier_contracts(tenant_id,carrier_id,mode,lane_code,status,valid_from);

CREATE TABLE IF NOT EXISTS carrier_settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    carrier_id UUID REFERENCES carrier_master(id),
    carrier_name VARCHAR(255) NOT NULL,
    shipment_id UUID REFERENCES shipments(id),
    tender_id UUID REFERENCES carrier_tenders(id),
    settlement_no VARCHAR(120) NOT NULL,
    amount NUMERIC(19,4) NOT NULL CHECK(amount >= 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,settlement_no)
);
CREATE INDEX IF NOT EXISTS ix_carrier_settlements_queue
    ON carrier_settlements(tenant_id,carrier_name,status,created_at DESC);

CREATE TABLE IF NOT EXISTS finance_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    note_no VARCHAR(120) NOT NULL,
    note_type VARCHAR(20) NOT NULL,
    invoice_id UUID REFERENCES commercial_invoices(id),
    shipment_id UUID REFERENCES shipments(id),
    amount NUMERIC(19,4) NOT NULL CHECK(amount > 0),
    currency VARCHAR(3) NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,note_no)
);
CREATE INDEX IF NOT EXISTS ix_finance_notes_invoice
    ON finance_notes(tenant_id,invoice_id,note_type,status);

CREATE TABLE IF NOT EXISTS finance_tax_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(80) NOT NULL,
    tax_name VARCHAR(160) NOT NULL,
    rate NUMERIC(9,4) NOT NULL DEFAULT 0,
    withholding_rate NUMERIC(9,4) NOT NULL DEFAULT 0,
    currency VARCHAR(3),
    valid_from DATE NOT NULL,
    valid_until DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(tenant_id,code)
);
CREATE INDEX IF NOT EXISTS ix_finance_tax_rules
    ON finance_tax_rules(tenant_id,active,valid_from DESC);

CREATE TABLE IF NOT EXISTS sla_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID REFERENCES shipments(id),
    exception_id UUID REFERENCES operational_exceptions(id),
    event_code VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    owner VARCHAR(255),
    escalated_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    resolution TEXT
);
CREATE INDEX IF NOT EXISTS ix_sla_instances_queue
    ON sla_instances(tenant_id,status,due_at);

CREATE TABLE IF NOT EXISTS exception_escalations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    exception_id UUID NOT NULL REFERENCES operational_exceptions(id) ON DELETE CASCADE,
    level_no INTEGER NOT NULL,
    target_owner VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    due_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    UNIQUE(tenant_id,exception_id,level_no)
);

CREATE TABLE IF NOT EXISTS ocean_shipping_instructions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    booking_id UUID NOT NULL REFERENCES ocean_bookings(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    shipper_json TEXT,
    consignee_json TEXT,
    notify_party_json TEXT,
    cargo_json TEXT,
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    UNIQUE(tenant_id,booking_id,version_no)
);

CREATE TABLE IF NOT EXISTS ocean_bills_of_lading (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    booking_id UUID NOT NULL REFERENCES ocean_bookings(id) ON DELETE CASCADE,
    bill_type VARCHAR(10) NOT NULL,
    bill_number VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    ebl_reference VARCHAR(255),
    approved_at TIMESTAMPTZ,
    issued_at TIMESTAMPTZ,
    supersedes_id UUID REFERENCES ocean_bills_of_lading(id),
    UNIQUE(tenant_id,bill_number,version_no)
);
CREATE INDEX IF NOT EXISTS ix_ocean_bl_booking
    ON ocean_bills_of_lading(tenant_id,booking_id,bill_type,status);

CREATE TABLE IF NOT EXISTS mobile_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(160) NOT NULL,
    device_type VARCHAR(30) NOT NULL,
    platform VARCHAR(40),
    app_version VARCHAR(60),
    user_id UUID REFERENCES users(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,device_id)
);

CREATE TABLE IF NOT EXISTS mobile_push_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(160) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,device_id,provider,token_hash)
);

CREATE TABLE IF NOT EXISTS mobile_sync_conflicts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    device_id VARCHAR(160) NOT NULL,
    operation_id VARCHAR(160) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    server_state_json TEXT,
    client_state_json TEXT,
    resolution_status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    resolved_at TIMESTAMPTZ,
    UNIQUE(tenant_id,device_id,operation_id)
);

CREATE TABLE IF NOT EXISTS accounting_exports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    period_id UUID REFERENCES finance_periods(id),
    export_type VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    row_count INTEGER NOT NULL DEFAULT 0,
    payload_hash VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_at TIMESTAMPTZ,
    notes TEXT
);

CREATE TABLE IF NOT EXISTS route_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID REFERENCES shipments(id),
    trip_id UUID REFERENCES trips(id),
    mode VARCHAR(40) NOT NULL,
    origin VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    route_json TEXT NOT NULL,
    distance_km NUMERIC(19,3),
    duration_minutes INTEGER,
    planned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(30) NOT NULL DEFAULT 'PLANNED'
);
CREATE INDEX IF NOT EXISTS ix_route_plans_shipment
    ON route_plans(tenant_id,shipment_id,planned_at DESC);

CREATE TABLE IF NOT EXISTS analytics_forecasts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    forecast_type VARCHAR(60) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    value NUMERIC(19,4) NOT NULL,
    confidence NUMERIC(9,4),
    methodology VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_analytics_forecasts
    ON analytics_forecasts(tenant_id,forecast_type,period_end DESC);

CREATE TABLE IF NOT EXISTS customs_submission_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    declaration_id UUID NOT NULL REFERENCES customs_declarations(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    external_reference VARCHAR(255),
    response_message TEXT,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,declaration_id,idempotency_key)
);

DO $$ DECLARE t text; BEGIN
  FOR t IN SELECT unnest(ARRAY[
    'carrier_contracts','carrier_settlements','finance_notes','finance_tax_rules',
    'sla_instances','exception_escalations','ocean_shipping_instructions','ocean_bills_of_lading',
    'mobile_devices','mobile_push_subscriptions','mobile_sync_conflicts','accounting_exports',
    'route_plans','analytics_forecasts','customs_submission_attempts'
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
