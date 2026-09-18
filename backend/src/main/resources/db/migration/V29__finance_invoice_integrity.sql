-- ============================================================================
-- AAL FINANCE INTEGRITY
-- V29
--
-- A shipment may have one canonical commercial invoice. Standalone invoices
-- remain valid because the unique index is intentionally partial.
--
-- If historical data already contains duplicate shipment invoices, migration
-- stops instead of silently merging or deleting financial records.
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM commercial_invoices
        WHERE shipment_id IS NOT NULL
        GROUP BY tenant_id, shipment_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V29 blocked: duplicate commercial invoices exist for one or more shipments. Reconcile those financial records before applying V29.';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_commercial_invoice_tenant_shipment
    ON commercial_invoices (tenant_id, shipment_id)
    WHERE shipment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_commercial_invoices_tenant_issue_date
    ON commercial_invoices (tenant_id, issue_date DESC);

CREATE INDEX IF NOT EXISTS idx_commercial_invoices_tenant_due_date
    ON commercial_invoices (tenant_id, due_date);

CREATE INDEX IF NOT EXISTS idx_finance_ledger_tenant_invoice_posted
    ON finance_ledger_entries (tenant_id, invoice_id, posted_at DESC);

CREATE INDEX IF NOT EXISTS idx_finance_ledger_tenant_currency_account
    ON finance_ledger_entries (tenant_id, currency, account_code, posted_at DESC);
