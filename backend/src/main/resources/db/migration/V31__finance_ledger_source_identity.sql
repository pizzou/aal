-- ============================================================================
-- AAL FINANCE LEDGER SOURCE IDENTITY
-- V31
--
-- FinanceLedgerEntry supports idempotent source-aware postings through
-- (source_type, source_id). V15 created the ledger before source identity was
-- introduced, so Hibernate validation correctly detects the schema drift.
--
-- source_id is intentionally nullable: not every ledger entry has a single
-- originating business record (for example, some aggregate/manual entries).
-- Existing rows therefore remain valid and no financial data is rewritten.
-- ============================================================================

ALTER TABLE finance_ledger_entries
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(80),
    ADD COLUMN IF NOT EXISTS source_id UUID;

CREATE INDEX IF NOT EXISTS idx_finance_ledger_source
    ON finance_ledger_entries (tenant_id, source_type, source_id, entry_type, currency);
