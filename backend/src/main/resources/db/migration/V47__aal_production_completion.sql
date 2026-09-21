-- AAL production completion: commercial immutability, master data, CRM,
-- document lifecycle, customs workflow, pricing rules and security events.

ALTER TABLE shipment_milestones ADD COLUMN IF NOT EXISTS customer_visible BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE commercial_quotes
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN IF NOT EXISTS incoterm VARCHAR(20),
    ADD COLUMN IF NOT EXISTS tax_rate NUMERIC(9,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS customs_cost NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS insurance_cost NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS customer_credit_terms VARCHAR(120),
    ADD COLUMN IF NOT EXISTS locked_amount NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS locked_currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS price_locked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE IF NOT EXISTS commercial_quote_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    quote_id UUID NOT NULL REFERENCES commercial_quotes(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    locked_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, quote_id, version_no)
);
CREATE INDEX IF NOT EXISTS ix_quote_versions_latest ON commercial_quote_versions(tenant_id,quote_id,version_no DESC);

-- Backfill every historical commercial quotation with an immutable version so existing
-- public links never fall back to a mutable live price.
INSERT INTO commercial_quote_versions(id,tenant_id,quote_id,version_no,snapshot_json,currency,amount,status,locked,locked_at,created_at)
SELECT gen_random_uuid(), q.tenant_id, q.id, 1,
       jsonb_build_object('quoteId',q.quote_id,'quoteDate',q.quote_date,'client',q.client,'route',q.route,'serviceType',q.service_type,'commodity',q.commodity,'chargeableWeightKg',q.chargeable_weight_kg,'supplierCost',q.supplier_cost,'otherCost',q.other_cost,'markupPercent',q.markup_percent,'quotedAmount',q.quoted_amount,'currency',COALESCE(q.currency,'USD'),'incoterm',q.incoterm,'taxRate',q.tax_rate,'taxAmount',q.tax_amount,'customsCost',q.customs_cost,'insuranceCost',q.insurance_cost)::text,
       COALESCE(q.currency,'USD'),COALESCE(q.quoted_amount,0),'LOCKED',true,now(),COALESCE(q.created_at,now())
FROM commercial_quotes q
WHERE NOT EXISTS (SELECT 1 FROM commercial_quote_versions v WHERE v.tenant_id=q.tenant_id AND v.quote_id=q.id);

ALTER TABLE commercial_quotes ADD COLUMN IF NOT EXISTS accepted_version_id UUID REFERENCES commercial_quote_versions(id);
ALTER TABLE commercial_quote_shares ADD COLUMN IF NOT EXISTS quote_version_id UUID REFERENCES commercial_quote_versions(id);
CREATE INDEX IF NOT EXISTS ix_quote_shares_version ON commercial_quote_shares(tenant_id,quote_version_id);
UPDATE commercial_quote_shares s SET quote_version_id=v.id
FROM commercial_quote_versions v
WHERE s.quote_version_id IS NULL AND v.tenant_id=s.tenant_id AND v.quote_id=s.quote_id AND v.version_no=1;

CREATE TABLE IF NOT EXISTS carrier_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    carrier_code VARCHAR(80) NOT NULL, carrier_name VARCHAR(255) NOT NULL, mode VARCHAR(40) NOT NULL,
    scac_iata_code VARCHAR(40), api_integration_code VARCHAR(80), contact_email VARCHAR(255),
    country_code VARCHAR(3), active BOOLEAN NOT NULL DEFAULT TRUE, metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,carrier_code)
);

CREATE TABLE IF NOT EXISTS lane_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    lane_code VARCHAR(120) NOT NULL, origin_code VARCHAR(120) NOT NULL, destination_code VARCHAR(120) NOT NULL,
    mode VARCHAR(40) NOT NULL, default_transit_minutes INTEGER, active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,lane_code)
);
CREATE INDEX IF NOT EXISTS ix_lane_lookup ON lane_master(tenant_id,origin_code,destination_code,mode,active);

CREATE TABLE IF NOT EXISTS location_master (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    location_code VARCHAR(80) NOT NULL, location_type VARCHAR(30) NOT NULL, name VARCHAR(255) NOT NULL,
    city VARCHAR(120), country_code VARCHAR(3), latitude DOUBLE PRECISION, longitude DOUBLE PRECISION,
    timezone VARCHAR(80), active BOOLEAN NOT NULL DEFAULT TRUE, metadata_json TEXT,
    UNIQUE(tenant_id,location_code)
);

CREATE TABLE IF NOT EXISTS pricing_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    rule_code VARCHAR(100) NOT NULL, priority INTEGER NOT NULL DEFAULT 100, mode VARCHAR(40),
    lane_code VARCHAR(120), customer_segment VARCHAR(120), commodity_group VARCHAR(120),
    min_weight_kg NUMERIC(18,3), max_weight_kg NUMERIC(18,3), min_charge NUMERIC(19,4),
    rate_per_kg NUMERIC(19,6), markup_percent NUMERIC(9,4), fuel_percent NUMERIC(9,4),
    tax_percent NUMERIC(9,4), currency VARCHAR(3) NOT NULL DEFAULT 'USD', valid_from DATE NOT NULL,
    valid_until DATE, active BOOLEAN NOT NULL DEFAULT TRUE, conditions_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,rule_code)
);
CREATE INDEX IF NOT EXISTS ix_pricing_rules_match ON pricing_rules(tenant_id,mode,lane_code,active,priority);

ALTER TABLE cargo_documents
    ADD COLUMN IF NOT EXISTS version_no INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS supersedes_document_id UUID REFERENCES cargo_documents(id),
    ADD COLUMN IF NOT EXISTS customer_visible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS uploaded_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS file_size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS mime_type VARCHAR(160),
    ADD COLUMN IF NOT EXISTS checksum_sha256 VARCHAR(128),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS ix_cargo_docs_version ON cargo_documents(tenant_id,shipment_id,document_type,version_no DESC);

CREATE TABLE IF NOT EXISTS crm_activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID REFERENCES client_records(id), activity_type VARCHAR(40) NOT NULL, subject VARCHAR(255) NOT NULL,
    body TEXT, owner UUID REFERENCES users(id), due_at TIMESTAMPTZ, completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_crm_activity_queue ON crm_activities(tenant_id,client_id,due_at,completed_at);

ALTER TABLE client_records
    ADD COLUMN IF NOT EXISTS lifecycle_stage VARCHAR(40) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN IF NOT EXISTS source_lead_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS credit_limit NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS credit_currency VARCHAR(3),
    ADD COLUMN IF NOT EXISTS payment_terms VARCHAR(120),
    ADD COLUMN IF NOT EXISTS tax_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS ix_client_lifecycle ON client_records(tenant_id,lifecycle_stage,client_status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_client_company_ci ON client_records(tenant_id,lower(client_company)) WHERE client_company IS NOT NULL AND btrim(client_company) <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uq_client_email_ci ON client_records(tenant_id,lower(email)) WHERE email IS NOT NULL AND btrim(email) <> '';

CREATE TABLE IF NOT EXISTS customs_workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id), declaration_id UUID REFERENCES customs_declarations(id),
    stage VARCHAR(50) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'OPEN', assigned_to UUID REFERENCES users(id),
    due_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, external_reference VARCHAR(160), notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_customs_workflow_queue ON customs_workflows(tenant_id,status,due_at);

CREATE TABLE IF NOT EXISTS notification_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    client_id UUID REFERENCES client_records(id), user_id UUID REFERENCES users(id), event_type VARCHAR(100) NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE, sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    whatsapp_enabled BOOLEAN NOT NULL DEFAULT FALSE, in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(tenant_id,client_id,user_id,event_type)
);

CREATE TABLE IF NOT EXISTS notification_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_type VARCHAR(100) NOT NULL, channel VARCHAR(30) NOT NULL, subject_template TEXT,
    body_template TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, version_no INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,event_type,channel,version_no)
);

CREATE TABLE IF NOT EXISTS security_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID REFERENCES tenants(id), user_id UUID REFERENCES users(id),
    event_type VARCHAR(80) NOT NULL, ip_address VARCHAR(80), user_agent TEXT, correlation_id VARCHAR(120),
    metadata_json TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_security_events ON security_events(tenant_id,created_at DESC,event_type);

DO $$ DECLARE t text; BEGIN
 FOR t IN SELECT unnest(ARRAY['commercial_quote_versions','carrier_master','lane_master','location_master','pricing_rules','crm_activities','customs_workflows','notification_preferences','notification_templates','security_events']) LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
   EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
   EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
 END LOOP;
END $$;
