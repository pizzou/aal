-- V88: lifecycle history is an audit record, not a child row to be silently
-- removed with an invoice. Invoice deletion is already blocked by V85; this
-- FK also protects the history if that guard is bypassed or changed later.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'commercial_invoice_lifecycle_history'::regclass
           AND confrelid = 'commercial_invoices'::regclass
           AND contype = 'f'
    LOOP
        EXECUTE format('ALTER TABLE commercial_invoice_lifecycle_history DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE commercial_invoice_lifecycle_history
    ADD CONSTRAINT fk_invoice_lifecycle_history_invoice_restrict
    FOREIGN KEY (invoice_id) REFERENCES commercial_invoices(id) ON DELETE RESTRICT;

-- Invoice lifecycle history is append-only. Corrections must be represented by
-- a subsequent lifecycle event rather than rewriting an earlier event.
DROP TRIGGER IF EXISTS trg_invoice_lifecycle_history_immutable ON commercial_invoice_lifecycle_history;
CREATE TRIGGER trg_invoice_lifecycle_history_immutable
BEFORE UPDATE OR DELETE ON commercial_invoice_lifecycle_history
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

-- A process crash after claiming a reminder must not leave it PENDING forever.
-- FinancialHardeningService safely reclaims PENDING rows older than 15 minutes;
-- the unique key and atomic upsert still prevent simultaneous duplicate claims.
CREATE INDEX IF NOT EXISTS ix_overdue_reminders_stale_pending
    ON finance_overdue_reminders(tenant_id, created_at)
    WHERE status = 'PENDING';
