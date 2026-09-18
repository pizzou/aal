-- Enterprise logistics core: CRM, quotes, milestones, workflows, SLAs,
-- integration reliability, claims and shipment parties.

CREATE TABLE tenant_profiles (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id),
 legal_name VARCHAR(255) NOT NULL, trading_name VARCHAR(255), registration_number VARCHAR(120), tax_number VARCHAR(120),
 country_code VARCHAR(3), city VARCHAR(120), timezone VARCHAR(80) DEFAULT 'Africa/Kigali', default_currency VARCHAR(3) DEFAULT 'USD',
 phone VARCHAR(80), email VARCHAR(255), website VARCHAR(255), logo_uri TEXT, settings_json TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenant_profiles(tenant_id,legal_name,trading_name)
SELECT id,name,name FROM tenants t WHERE NOT EXISTS (SELECT 1 FROM tenant_profiles p WHERE p.tenant_id=t.id);

CREATE TABLE logistics_contacts (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), client_id UUID REFERENCES client_records(id),
 first_name VARCHAR(120), last_name VARCHAR(120), job_title VARCHAR(160), email VARCHAR(255), phone VARCHAR(80), whatsapp VARCHAR(80),
 preferred_language VARCHAR(20), preferred_channel VARCHAR(30) DEFAULT 'EMAIL', is_primary BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_logistics_contacts_client ON logistics_contacts(tenant_id,client_id);

CREATE TABLE shipment_parties (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 party_type VARCHAR(40) NOT NULL, company_name VARCHAR(255) NOT NULL, contact_name VARCHAR(255), email VARCHAR(255), phone VARCHAR(80),
 address TEXT, country_code VARCHAR(3), city VARCHAR(120), tax_number VARCHAR(120), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,shipment_id,party_type)
);
CREATE INDEX idx_shipment_parties_shipment ON shipment_parties(tenant_id,shipment_id);

CREATE TABLE logistics_quotes (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), quote_number VARCHAR(100) NOT NULL,
 client_id UUID REFERENCES client_records(id), shipment_id UUID REFERENCES shipments(id), status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 currency VARCHAR(3) NOT NULL DEFAULT 'USD', valid_until TIMESTAMPTZ, subtotal NUMERIC(19,4) NOT NULL DEFAULT 0,
 tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0, discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0, total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
 target_margin_percent NUMERIC(9,4), terms TEXT, created_by UUID, approved_by UUID, approved_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,quote_number)
);
CREATE TABLE logistics_quote_lines (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), quote_id UUID NOT NULL REFERENCES logistics_quotes(id),
 line_no INTEGER NOT NULL, description VARCHAR(500) NOT NULL, mode VARCHAR(30), quantity NUMERIC(19,4) NOT NULL DEFAULT 1,
 unit_price NUMERIC(19,4) NOT NULL DEFAULT 0, cost_amount NUMERIC(19,4) NOT NULL DEFAULT 0, sell_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
 currency VARCHAR(3) NOT NULL DEFAULT 'USD', created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,quote_id,line_no)
);
CREATE INDEX idx_quote_lines_quote ON logistics_quote_lines(tenant_id,quote_id);

CREATE TABLE shipment_milestones (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 milestone_code VARCHAR(80) NOT NULL, milestone_name VARCHAR(255) NOT NULL, sequence_no INTEGER NOT NULL DEFAULT 0,
 planned_at TIMESTAMPTZ, estimated_at TIMESTAMPTZ, actual_at TIMESTAMPTZ, status VARCHAR(30) NOT NULL DEFAULT 'PLANNED', source VARCHAR(40) DEFAULT 'SYSTEM', notes TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,shipment_id,milestone_code)
);
CREATE INDEX idx_milestones_due ON shipment_milestones(tenant_id,status,estimated_at);

CREATE TABLE logistics_sla_policies (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), name VARCHAR(160) NOT NULL,
 event_code VARCHAR(80) NOT NULL, target_minutes INTEGER NOT NULL, severity VARCHAR(20) NOT NULL DEFAULT 'HIGH', active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,name,event_code)
);
CREATE TABLE logistics_workflows (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), name VARCHAR(160) NOT NULL,
 trigger_event VARCHAR(100) NOT NULL, condition_json TEXT, action_json TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workflows_trigger ON logistics_workflows(tenant_id,trigger_event,active);

CREATE TABLE logistics_tasks (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID REFERENCES shipments(id),
 title VARCHAR(255) NOT NULL, description TEXT, task_type VARCHAR(80), priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM', status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
 assigned_to UUID REFERENCES users(id), due_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tasks_queue ON logistics_tasks(tenant_id,status,priority,due_at);

CREATE TABLE integration_outbox (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), aggregate_type VARCHAR(80) NOT NULL,
 aggregate_id UUID, event_type VARCHAR(120) NOT NULL, idempotency_key VARCHAR(180) NOT NULL, payload_json TEXT NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 locked_at TIMESTAMPTZ, last_error TEXT, provider VARCHAR(120), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ,
 UNIQUE(tenant_id,idempotency_key)
);
CREATE INDEX idx_outbox_queue ON integration_outbox(status,next_attempt_at);

CREATE TABLE logistics_claims (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 claim_number VARCHAR(100) NOT NULL, claim_type VARCHAR(60) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
 responsible_party VARCHAR(255), claimed_amount NUMERIC(19,4) NOT NULL DEFAULT 0, currency VARCHAR(3) NOT NULL DEFAULT 'USD',
 description TEXT, evidence_json TEXT, filed_at TIMESTAMPTZ, resolved_at TIMESTAMPTZ, settlement_amount NUMERIC(19,4), resolution TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,claim_number)
);
CREATE INDEX idx_claims_shipment ON logistics_claims(tenant_id,shipment_id,status);

CREATE TABLE shipment_insurance (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id), shipment_id UUID NOT NULL REFERENCES shipments(id),
 policy_number VARCHAR(120), insurer VARCHAR(255), insured_value NUMERIC(19,4) NOT NULL DEFAULT 0, currency VARCHAR(3) NOT NULL DEFAULT 'USD',
 premium NUMERIC(19,4) NOT NULL DEFAULT 0, coverage TEXT, exclusions TEXT, status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,shipment_id)
);

DO $$ DECLARE t text; BEGIN
 FOR t IN SELECT unnest(ARRAY['tenant_profiles','logistics_contacts','shipment_parties','logistics_quotes','logistics_quote_lines','shipment_milestones','logistics_sla_policies','logistics_workflows','logistics_tasks','integration_outbox','logistics_claims','shipment_insurance']) LOOP
   EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
   EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
   EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)', t, t);
 END LOOP;
END $$;

-- Standard operational milestone templates are created per tenant and can be extended by mode-specific modules.
INSERT INTO logistics_sla_policies(tenant_id,name,event_code,target_minutes,severity)
SELECT t.id,'Booking acknowledgement','BOOKING_ACK',60,'HIGH' FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM logistics_sla_policies s WHERE s.tenant_id=t.id AND s.event_code='BOOKING_ACK');
INSERT INTO logistics_sla_policies(tenant_id,name,event_code,target_minutes,severity)
SELECT t.id,'Exception response','EXCEPTION_RESPONSE',120,'CRITICAL' FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM logistics_sla_policies s WHERE s.tenant_id=t.id AND s.event_code='EXCEPTION_RESPONSE');
