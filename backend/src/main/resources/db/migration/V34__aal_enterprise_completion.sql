-- AAL enterprise completion layer: multimodal detail, finance controls, MFA,
-- geofences, notification queue, integration registry and secure tracking.

ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_secret_enc TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_verified_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS tracking_expires_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS tracking_revoked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS before_state TEXT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS after_state TEXT;
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(120);

CREATE TABLE IF NOT EXISTS finance_fx_rates (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 rate_date DATE NOT NULL, base_currency VARCHAR(10) NOT NULL, quote_currency VARCHAR(10) NOT NULL,
 rate NUMERIC(24,10) NOT NULL CHECK(rate>0), source VARCHAR(120), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,rate_date,base_currency,quote_currency)
);
CREATE INDEX IF NOT EXISTS ix_fx_tenant_date ON finance_fx_rates(tenant_id,rate_date DESC);

CREATE TABLE IF NOT EXISTS finance_periods (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 period_start DATE NOT NULL, period_end DATE NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 closed_at TIMESTAMPTZ, closed_by UUID REFERENCES users(id), notes TEXT,
 CHECK(period_end>=period_start), UNIQUE(tenant_id,period_start,period_end)
);

CREATE TABLE IF NOT EXISTS finance_adjustments (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 adjustment_no VARCHAR(120) NOT NULL, adjustment_type VARCHAR(20) NOT NULL,
 invoice_id UUID REFERENCES commercial_invoices(id), shipment_id UUID REFERENCES shipments(id),
 amount NUMERIC(19,4) NOT NULL CHECK(amount>0), currency VARCHAR(10) NOT NULL,
 reason TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', created_by UUID REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), approved_at TIMESTAMPTZ,
 UNIQUE(tenant_id,adjustment_no)
);

CREATE TABLE IF NOT EXISTS aal_geofences (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 name VARCHAR(160) NOT NULL, latitude DOUBLE PRECISION NOT NULL, longitude DOUBLE PRECISION NOT NULL,
 radius_m INTEGER NOT NULL CHECK(radius_m>0), active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(tenant_id,name)
);

CREATE TABLE IF NOT EXISTS transport_leg_milestones (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 leg_id UUID NOT NULL REFERENCES transport_legs(id) ON DELETE CASCADE, milestone_type VARCHAR(80) NOT NULL,
 location VARCHAR(255), planned_at TIMESTAMPTZ, actual_at TIMESTAMPTZ, status VARCHAR(30) NOT NULL DEFAULT 'PLANNED',
 notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_leg_milestones ON transport_leg_milestones(tenant_id,leg_id,actual_at);

CREATE TABLE IF NOT EXISTS transport_leg_documents (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 leg_id UUID NOT NULL REFERENCES transport_legs(id) ON DELETE CASCADE, document_type VARCHAR(80) NOT NULL,
 document_uri TEXT NOT NULL, customer_visible BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transport_leg_costs (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 leg_id UUID NOT NULL REFERENCES transport_legs(id) ON DELETE CASCADE, description VARCHAR(255) NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK(amount>=0), currency VARCHAR(10) NOT NULL, supplier VARCHAR(255),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS notification_queue (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 shipment_id UUID REFERENCES shipments(id), channel VARCHAR(30) NOT NULL, recipient VARCHAR(500),
 event_type VARCHAR(80) NOT NULL, subject VARCHAR(500), body TEXT NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_notification_queue ON notification_queue(tenant_id,status,next_attempt_at);

CREATE TABLE IF NOT EXISTS integration_registry (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 code VARCHAR(80) NOT NULL, display_name VARCHAR(180) NOT NULL, protocol VARCHAR(40) NOT NULL,
 base_url TEXT, enabled BOOLEAN NOT NULL DEFAULT FALSE, health_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CONFIGURED',
 last_success_at TIMESTAMPTZ, last_failure_at TIMESTAMPTZ, last_error TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,code)
);

CREATE TABLE IF NOT EXISTS integration_attempts (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 integration_code VARCHAR(80) NOT NULL, operation VARCHAR(120) NOT NULL, idempotency_key VARCHAR(255),
 status VARCHAR(30) NOT NULL, response_code INTEGER, error_detail TEXT, attempts INTEGER NOT NULL DEFAULT 1,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_integration_attempts ON integration_attempts(tenant_id,integration_code,created_at DESC);

CREATE TABLE IF NOT EXISTS file_scan_records (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id UUID NOT NULL REFERENCES tenants(id),
 document_uri TEXT NOT NULL, sha256 VARCHAR(128) NOT NULL, scanner VARCHAR(120) NOT NULL,
 status VARCHAR(30) NOT NULL, threat_name VARCHAR(255), scanned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$ DECLARE t text; BEGIN FOR t IN SELECT unnest(ARRAY['finance_fx_rates','finance_periods','finance_adjustments','aal_geofences','transport_leg_milestones','transport_leg_documents','transport_leg_costs','notification_queue','integration_registry','integration_attempts','file_scan_records']) LOOP EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t); EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t); EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t); EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t); END LOOP; END $$;
ALTER TABLE transport_legs ADD COLUMN IF NOT EXISTS equipment_reference VARCHAR(160);
ALTER TABLE transport_legs ADD COLUMN IF NOT EXISTS notes TEXT;
CREATE INDEX IF NOT EXISTS ix_transport_legs_tenant_sequence ON transport_legs(tenant_id,shipment_id,sequence_no);
