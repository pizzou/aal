-- Supporting indexes for financial reconciliation, retry-safe imports and
-- notification dead-letter operations. These indexes do not rewrite records.
CREATE INDEX IF NOT EXISTS idx_finance_ledger_reconciliation
    ON finance_ledger_entries (tenant_id, currency, posted_at, account_code, entry_type);

CREATE INDEX IF NOT EXISTS idx_aal_import_batch_tenant_status_started
    ON aal_import_batches (tenant_id, status, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_queue_dead_letter
    ON notification_queue (tenant_id, created_at DESC)
    WHERE status = 'DEAD_LETTER';
