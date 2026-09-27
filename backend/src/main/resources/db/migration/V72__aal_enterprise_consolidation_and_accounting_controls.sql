-- AAL enterprise hardening: consolidation and an explicit chart of accounts.
-- Existing ledger entries remain backward compatible; these tables provide the
-- missing controlled master data needed for professional freight forwarding.

CREATE TABLE IF NOT EXISTS shipment_consolidations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    consolidation_reference VARCHAR(100) NOT NULL,
    mode VARCHAR(30) NOT NULL,
    master_reference VARCHAR(100),
    origin_code VARCHAR(20),
    destination_code VARCHAR(20),
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    planned_departure TIMESTAMPTZ,
    planned_arrival TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, consolidation_reference)
);

CREATE TABLE IF NOT EXISTS shipment_consolidation_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    consolidation_id UUID NOT NULL REFERENCES shipment_consolidations(id) ON DELETE CASCADE,
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    house_reference VARCHAR(100),
    role VARCHAR(30) NOT NULL DEFAULT 'HOUSE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, consolidation_id, shipment_id),
    UNIQUE (tenant_id, shipment_id)
);

CREATE INDEX IF NOT EXISTS ix_consolidation_members_lookup
    ON shipment_consolidation_members(tenant_id, consolidation_id, shipment_id);

CREATE TABLE IF NOT EXISTS finance_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    account_code VARCHAR(50) NOT NULL,
    account_name VARCHAR(160) NOT NULL,
    account_type VARCHAR(30) NOT NULL,
    parent_code VARCHAR(50),
    normal_balance VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, account_code)
);

CREATE INDEX IF NOT EXISTS ix_finance_accounts_type
    ON finance_accounts(tenant_id, account_type, active);

DO $$
DECLARE t UUID;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        INSERT INTO finance_accounts(tenant_id,account_code,account_name,account_type,parent_code,normal_balance)
        VALUES
          (t,'1000-CASH','Cash and Bank','ASSET',NULL,'DEBIT'),
          (t,'1100-ACCOUNTS_RECEIVABLE','Accounts Receivable','ASSET',NULL,'DEBIT'),
          (t,'1200-OTHER_RECEIVABLES','Other Receivables','ASSET',NULL,'DEBIT'),
          (t,'2000-ACCOUNTS_PAYABLE','Accounts Payable','LIABILITY',NULL,'CREDIT'),
          (t,'2100-TAX_PAYABLE','Tax Payable','LIABILITY',NULL,'CREDIT'),
          (t,'3000-OWNER_EQUITY','Owner Equity','EQUITY',NULL,'CREDIT'),
          (t,'4000-FREIGHT_REVENUE','Freight Revenue','REVENUE',NULL,'CREDIT'),
          (t,'4100-ACCESSORIAL_REVENUE','Accessorial Revenue','REVENUE',NULL,'CREDIT'),
          (t,'5100-OPERATING_EXPENSE','Operating Expense','EXPENSE',NULL,'DEBIT'),
          (t,'5200-CARRIER_COST','Carrier Cost','EXPENSE',NULL,'DEBIT')
        ON CONFLICT (tenant_id,account_code) DO NOTHING;
    END LOOP;
END $$;

ALTER TABLE shipment_consolidations ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_consolidations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_shipment_consolidations ON shipment_consolidations;
CREATE POLICY tenant_isolation_shipment_consolidations ON shipment_consolidations
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE shipment_consolidation_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_consolidation_members FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_shipment_consolidation_members ON shipment_consolidation_members;
CREATE POLICY tenant_isolation_shipment_consolidation_members ON shipment_consolidation_members
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE finance_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_accounts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_finance_accounts ON finance_accounts;
CREATE POLICY tenant_isolation_finance_accounts ON finance_accounts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Case-insensitive protection closes a common duplicate-reference failure mode
-- while preserving the existing canonical unique constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipments_tenant_reference_ci
    ON shipments(tenant_id, lower(reference_code));
