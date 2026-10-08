-- V83: production financial-document hardening
-- Adds explicit invoice lifecycle history, formal finance-note approvals,
-- jurisdiction metadata, immutable financial-document archive, and expiring links.

ALTER TABLE commercial_invoices
    ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'ISSUED',
    ADD COLUMN IF NOT EXISTS credit_note_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS debit_note_amount NUMERIC(19,4) NOT NULL DEFAULT 0;

-- Backfill durable receipt numbers for legacy payments before any new receipt is issued.
WITH existing AS (
    SELECT tenant_id,
           EXTRACT(YEAR FROM created_at)::INTEGER AS fiscal_year,
           COALESCE(MAX(substring(receipt_no FROM '([0-9]+)$')::BIGINT),0) AS max_no
      FROM commercial_payments
     WHERE receipt_no IS NOT NULL
       AND receipt_no ~ '^AAL-RCT-[0-9]{4}-[0-9]+$'
     GROUP BY tenant_id, EXTRACT(YEAR FROM created_at)
),
numbered AS (
    SELECT p.id,
           p.tenant_id,
           EXTRACT(YEAR FROM p.created_at)::INTEGER AS fiscal_year,
           COALESCE(e.max_no,0) +
           ROW_NUMBER() OVER (
               PARTITION BY p.tenant_id, EXTRACT(YEAR FROM p.created_at)
               ORDER BY p.created_at, p.id
           ) AS new_no
      FROM commercial_payments p
      LEFT JOIN existing e
        ON e.tenant_id=p.tenant_id
       AND e.fiscal_year=EXTRACT(YEAR FROM p.created_at)::INTEGER
     WHERE p.receipt_no IS NULL
)
UPDATE commercial_payments p
   SET receipt_no = 'AAL-RCT-' || n.fiscal_year || '-' || LPAD(n.new_no::TEXT, 6, '0')
  FROM numbered n
 WHERE p.id=n.id AND p.receipt_no IS NULL;

INSERT INTO finance_document_sequences(tenant_id,document_type,fiscal_year,last_value)
SELECT tenant_id,'RECEIPT',fiscal_year,MAX(suffix)
  FROM (
      SELECT tenant_id,
             EXTRACT(YEAR FROM created_at)::INTEGER AS fiscal_year,
             substring(receipt_no FROM '([0-9]+)$')::BIGINT AS suffix
        FROM commercial_payments
       WHERE receipt_no IS NOT NULL
         AND receipt_no ~ '^AAL-RCT-[0-9]{4}-[0-9]+$'
  ) q
 GROUP BY tenant_id,fiscal_year
ON CONFLICT(tenant_id,document_type,fiscal_year)
DO UPDATE SET last_value=GREATEST(finance_document_sequences.last_value,EXCLUDED.last_value),
              updated_at=now();

UPDATE commercial_invoices
   SET lifecycle_status = 'PAID'
 WHERE lifecycle_status = 'ISSUED'
   AND amount_paid + credit_note_amount >= invoice_amount + debit_note_amount
   AND invoice_amount > 0;


CREATE TABLE IF NOT EXISTS commercial_invoice_lifecycle_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    invoice_id UUID NOT NULL REFERENCES commercial_invoices(id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    reason TEXT,
    changed_by UUID REFERENCES users(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_invoice_lifecycle_history
    ON commercial_invoice_lifecycle_history(tenant_id, invoice_id, changed_at DESC);

INSERT INTO commercial_invoice_lifecycle_history
    (tenant_id, invoice_id, from_status, to_status, reason)
SELECT tenant_id, id, NULL, lifecycle_status, 'Initial lifecycle status established by V83'
  FROM commercial_invoices i
 WHERE NOT EXISTS (
    SELECT 1 FROM commercial_invoice_lifecycle_history h
     WHERE h.tenant_id=i.tenant_id AND h.invoice_id=i.id
 );

ALTER TABLE finance_notes
    ADD COLUMN IF NOT EXISTS approved_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS voided_by UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS voided_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS posted_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS finance_tax_jurisdictions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    jurisdiction_code VARCHAR(80) NOT NULL,
    legal_name VARCHAR(255) NOT NULL,
    tax_registration_no VARCHAR(120),
    country_code VARCHAR(3) NOT NULL,
    address TEXT,
    invoice_prefix VARCHAR(40),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, jurisdiction_code)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_finance_tax_jurisdiction_active
    ON finance_tax_jurisdictions(tenant_id)
    WHERE active=true;


CREATE TABLE IF NOT EXISTS finance_overdue_reminders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    invoice_id UUID NOT NULL REFERENCES commercial_invoices(id) ON DELETE CASCADE,
    reminder_date DATE NOT NULL,
    reminder_level INTEGER NOT NULL DEFAULT 1,
    recipient_email VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, invoice_id, reminder_date, reminder_level)
);

CREATE TABLE IF NOT EXISTS financial_document_archives (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_type VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    storage_provider VARCHAR(40) NOT NULL,
    object_key TEXT,
    content BYTEA,
    sha256 VARCHAR(64) NOT NULL,
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, document_type, source_id, sha256)
);
CREATE INDEX IF NOT EXISTS ix_financial_document_archives_source
    ON financial_document_archives(tenant_id, document_type, source_id, created_at DESC);

CREATE TABLE IF NOT EXISTS financial_document_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    archive_id UUID NOT NULL REFERENCES financial_document_archives(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_financial_document_links_expiry
    ON financial_document_links(tenant_id, expires_at);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'commercial_invoice_lifecycle_history',
        'finance_tax_jurisdictions',
        'finance_overdue_reminders',
        'financial_document_archives',
        'financial_document_links'
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

CREATE OR REPLACE FUNCTION public.prevent_immutable_financial_document_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Immutable financial document records cannot be changed or deleted';
END;
$$;

DROP TRIGGER IF EXISTS trg_financial_document_archives_immutable ON financial_document_archives;
CREATE TRIGGER trg_financial_document_archives_immutable
BEFORE UPDATE OR DELETE ON financial_document_archives
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

DROP TRIGGER IF EXISTS trg_invoice_lifecycle_history_immutable ON commercial_invoice_lifecycle_history;
CREATE TRIGGER trg_invoice_lifecycle_history_immutable
BEFORE UPDATE OR DELETE ON commercial_invoice_lifecycle_history
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();
