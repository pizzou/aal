-- AAL finance extension for the workbook concepts that are not already
-- represented in the canonical financial model.
--
-- The existing commercial_payments row remains the only source of truth for
-- payment/date/total-income. This migration adds only:
--   * configurable income source classification
--   * configurable bank destinations
--   * configurable percentage allocation rules
--   * historical payment allocation percentages
--
-- No workbook rows, bank names, people, percentages, dates or amounts are
-- seeded here.

CREATE TABLE IF NOT EXISTS finance_income_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_finance_income_source_code UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS finance_bank_destinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    account_reference VARCHAR(160),
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_finance_bank_destination_code UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS finance_income_allocation_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    income_source_id UUID NOT NULL REFERENCES finance_income_sources(id),
    bank_destination_id UUID NOT NULL REFERENCES finance_bank_destinations(id),
    percentage NUMERIC(7,4) NOT NULL CHECK (percentage >= 0 AND percentage <= 100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_finance_income_allocation_rule
        UNIQUE (tenant_id, income_source_id, bank_destination_id)
);

ALTER TABLE commercial_payments
    ADD COLUMN IF NOT EXISTS income_source_id UUID
        REFERENCES finance_income_sources(id);

CREATE TABLE IF NOT EXISTS commercial_payment_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    payment_id UUID NOT NULL REFERENCES commercial_payments(id) ON DELETE CASCADE,
    bank_destination_id UUID NOT NULL REFERENCES finance_bank_destinations(id),
    percentage NUMERIC(7,4) NOT NULL CHECK (percentage >= 0 AND percentage <= 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_commercial_payment_allocation
        UNIQUE (payment_id, bank_destination_id)
);

CREATE INDEX IF NOT EXISTS ix_finance_income_sources_tenant_active
    ON finance_income_sources(tenant_id, active, name);

CREATE INDEX IF NOT EXISTS ix_finance_bank_destinations_tenant_active
    ON finance_bank_destinations(tenant_id, active, name);

CREATE INDEX IF NOT EXISTS ix_finance_income_allocation_rules_tenant_source
    ON finance_income_allocation_rules(tenant_id, income_source_id, active);

CREATE INDEX IF NOT EXISTS ix_commercial_payments_income_source
    ON commercial_payments(tenant_id, income_source_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_commercial_payment_allocations_tenant_payment
    ON commercial_payment_allocations(tenant_id, payment_id);

ALTER TABLE finance_income_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_income_sources FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_finance_income_sources ON finance_income_sources;
CREATE POLICY tenant_isolation_finance_income_sources
    ON finance_income_sources
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_bank_destinations ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_bank_destinations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_finance_bank_destinations ON finance_bank_destinations;
CREATE POLICY tenant_isolation_finance_bank_destinations
    ON finance_bank_destinations
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_income_allocation_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_income_allocation_rules FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_finance_income_allocation_rules ON finance_income_allocation_rules;
CREATE POLICY tenant_isolation_finance_income_allocation_rules
    ON finance_income_allocation_rules
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE commercial_payment_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE commercial_payment_allocations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_commercial_payment_allocations
    ON commercial_payment_allocations;
CREATE POLICY tenant_isolation_commercial_payment_allocations
    ON commercial_payment_allocations
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Reporting view: "INCOME" is deliberately calculated from the canonical
-- payment amount and the historical allocation percentage. It is not stored
-- as another amount column and therefore cannot diverge from the payment.
CREATE OR REPLACE VIEW finance_income_allocation_report AS
SELECT
    p.tenant_id,
    p.id AS payment_id,
    p.invoice_id,
    p.created_at AS payment_date,
    p.amount AS total_income,
    p.currency,
    s.id AS income_source_id,
    s.code AS income_source_code,
    s.name AS income_source_name,
    a.bank_destination_id,
    b.code AS bank_code,
    b.name AS bank_name,
    a.percentage,
    ROUND((p.amount * a.percentage / 100.0), 4) AS allocated_income
FROM commercial_payments p
JOIN commercial_payment_allocations a
  ON a.payment_id = p.id
 AND a.tenant_id = p.tenant_id
JOIN finance_bank_destinations b
  ON b.id = a.bank_destination_id
 AND b.tenant_id = p.tenant_id
LEFT JOIN finance_income_sources s
  ON s.id = p.income_source_id
 AND s.tenant_id = p.tenant_id;

ALTER VIEW finance_income_allocation_report SET (security_invoker = true);
