-- This is a RULES-BASED rate engine — configurable rate cards computed
-- deterministically, not "AI-driven" market pricing (which would require live
-- market-rate data feeds this platform has no access to, see README). What this
-- DOES replace honestly: a spreadsheet of "if AIR and under 500kg, charge X" rules,
-- now stored, editable, and computed consistently via one code path instead of
-- copy-pasted formulas across cells.

CREATE TABLE rate_cards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    transport_mode VARCHAR(20) NOT NULL,
    base_rate_per_kg NUMERIC(10,2) NOT NULL,
    min_charge NUMERIC(10,2) NOT NULL DEFAULT 0,
    fuel_surcharge_percent NUMERIC(5,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, transport_mode),
    CONSTRAINT chk_rate_non_negative CHECK (base_rate_per_kg >= 0),
    CONSTRAINT chk_min_charge_non_negative CHECK (min_charge >= 0),
    CONSTRAINT chk_fuel_surcharge_range CHECK (fuel_surcharge_percent BETWEEN 0 AND 100)
);

CREATE TABLE accessorial_charges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(50) NOT NULL,
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    UNIQUE (tenant_id, code),
    CONSTRAINT chk_accessorial_amount_non_negative CHECK (amount >= 0)
);

ALTER TABLE rate_cards ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_cards FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_rate_cards ON rate_cards
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE accessorial_charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE accessorial_charges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_accessorial_charges ON accessorial_charges
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
