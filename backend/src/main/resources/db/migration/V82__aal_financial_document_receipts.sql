-- ============================================================================
-- AAL FINANCIAL DOCUMENTS
-- V82
-- ============================================================================
ALTER TABLE commercial_payments
    ADD COLUMN IF NOT EXISTS receipt_no VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uk_commercial_payment_receipt_no
    ON commercial_payments(tenant_id, receipt_no)
    WHERE receipt_no IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_commercial_payments_tenant_created
    ON commercial_payments(tenant_id, created_at DESC);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'commercial_payments'
          AND policyname = 'tenant_isolation_commercial_payments'
    ) THEN
        ALTER TABLE commercial_payments ENABLE ROW LEVEL SECURITY;
        ALTER TABLE commercial_payments FORCE ROW LEVEL SECURITY;
        CREATE POLICY tenant_isolation_commercial_payments
            ON commercial_payments
            USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
    END IF;
END $$;
