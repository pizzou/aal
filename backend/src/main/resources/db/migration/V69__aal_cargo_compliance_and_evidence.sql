-- Cargo compliance and immutable evidence hardening.
ALTER TABLE dangerous_goods_declarations
    ADD COLUMN IF NOT EXISTS net_quantity NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS package_type VARCHAR(80),
    ADD COLUMN IF NOT EXISTS handling_instruction VARCHAR(160),
    ADD COLUMN IF NOT EXISTS cao BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS passenger_aircraft_restricted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS acceptance_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';

ALTER TABLE customs_declarations
    ADD COLUMN IF NOT EXISTS customs_value NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS duty_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rejection_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS rejection_message TEXT,
    ADD COLUMN IF NOT EXISTS resubmission_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE cargo_documents
    ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(60) NOT NULL DEFAULT 'OBJECT_STORAGE',
    ADD COLUMN IF NOT EXISTS object_version VARCHAR(255),
    ADD COLUMN IF NOT EXISTS encryption_key_ref VARCHAR(255),
    ADD COLUMN IF NOT EXISTS retention_until DATE,
    ADD COLUMN IF NOT EXISTS immutable_evidence BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS evidence_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS upload_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';

CREATE TABLE IF NOT EXISTS rate_card_components (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    rate_card_id UUID NOT NULL REFERENCES rate_cards(id) ON DELETE CASCADE,
    component_code VARCHAR(80) NOT NULL,
    component_name VARCHAR(160) NOT NULL,
    calculation_type VARCHAR(32) NOT NULL,
    amount NUMERIC(19,6) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    minimum_amount NUMERIC(19,4),
    maximum_amount NUMERIC(19,4),
    taxable BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from DATE NOT NULL,
    valid_until DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,rate_card_id,component_code,valid_from)
);
CREATE INDEX IF NOT EXISTS ix_rate_card_components_match
    ON rate_card_components(tenant_id,rate_card_id,active,valid_from DESC);

DO $$ DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY['rate_card_components'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I',t,t);
    EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',t,t);
  END LOOP;
END $$;
