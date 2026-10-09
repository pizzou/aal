-- Configurable net-profit allocation rules and immutable distribution instructions.
-- These are not payment records and do not trigger real bank transfers.
CREATE TABLE IF NOT EXISTS finance_profit_allocation_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    bank_destination_id UUID NOT NULL REFERENCES finance_bank_destinations(id),
    percentage NUMERIC(7,4) NOT NULL CHECK (percentage >= 0 AND percentage <= 100),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_finance_profit_allocation_rule UNIQUE (tenant_id, bank_destination_id)
);

CREATE TABLE IF NOT EXISTS finance_profit_allocation_runs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    shipment_reference VARCHAR(120) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    revenue NUMERIC(19,4) NOT NULL,
    supplier_paid NUMERIC(19,4) NOT NULL,
    other_expenses NUMERIC(19,4) NOT NULL,
    net_profit NUMERIC(19,4) NOT NULL CHECK (net_profit > 0),
    profit_basis VARCHAR(240) NOT NULL,
    transfer_status VARCHAR(32) NOT NULL DEFAULT 'INSTRUCTIONS_ONLY',
    calculation_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_finance_profit_allocation_snapshot
        UNIQUE (tenant_id, shipment_id, calculation_fingerprint)
);

CREATE TABLE IF NOT EXISTS finance_profit_allocation_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    allocation_run_id UUID NOT NULL REFERENCES finance_profit_allocation_runs(id) ON DELETE CASCADE,
    bank_destination_id UUID NOT NULL REFERENCES finance_bank_destinations(id),
    bank_code VARCHAR(80) NOT NULL,
    bank_name VARCHAR(160) NOT NULL,
    account_reference VARCHAR(160),
    percentage NUMERIC(7,4) NOT NULL CHECK (percentage >= 0 AND percentage <= 100),
    allocated_amount NUMERIC(19,4) NOT NULL CHECK (allocated_amount >= 0),
    currency VARCHAR(3) NOT NULL,
    transfer_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SENT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_finance_profit_allocation_item UNIQUE (allocation_run_id, bank_destination_id)
);

CREATE INDEX IF NOT EXISTS ix_finance_profit_rules_tenant_active
    ON finance_profit_allocation_rules(tenant_id, active, bank_destination_id);
CREATE INDEX IF NOT EXISTS ix_finance_profit_runs_tenant_date
    ON finance_profit_allocation_runs(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_finance_profit_items_tenant_run
    ON finance_profit_allocation_items(tenant_id, allocation_run_id);

DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'finance_profit_allocation_rules',
    'finance_profit_allocation_runs',
    'finance_profit_allocation_items'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I', t, t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',
      t, t
    );
  END LOOP;
END $$;

-- Distribution snapshots are historical financial evidence and are immutable.
DROP TRIGGER IF EXISTS trg_finance_profit_allocation_runs_immutable ON finance_profit_allocation_runs;
CREATE TRIGGER trg_finance_profit_allocation_runs_immutable
BEFORE UPDATE OR DELETE ON finance_profit_allocation_runs
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

DROP TRIGGER IF EXISTS trg_finance_profit_allocation_items_immutable ON finance_profit_allocation_items;
CREATE TRIGGER trg_finance_profit_allocation_items_immutable
BEFORE UPDATE OR DELETE ON finance_profit_allocation_items
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();
