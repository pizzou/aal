-- V85: close gaps in tax configuration, customer statements and immutable financial history.
-- Tax rules that previously had no jurisdiction scope are deliberately disabled until reviewed.

ALTER TABLE commercial_invoices
    ADD COLUMN IF NOT EXISTS subtotal_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_jurisdiction_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS tax_legal_name_snapshot VARCHAR(255),
    ADD COLUMN IF NOT EXISTS tax_registration_snapshot VARCHAR(120),
    ADD COLUMN IF NOT EXISTS tax_address_snapshot TEXT,
    ADD COLUMN IF NOT EXISTS tax_country_snapshot VARCHAR(3),
    ADD COLUMN IF NOT EXISTS tax_inclusive BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE commercial_invoices ALTER COLUMN tax_code TYPE VARCHAR(80);

UPDATE commercial_invoices
   SET subtotal_amount = invoice_amount
 WHERE subtotal_amount = 0 AND invoice_amount <> 0;

-- The old rule table was tenant/code unique and could not represent a tax code
-- used by more than one jurisdiction or more than one effective period.
ALTER TABLE finance_tax_rules
    ADD COLUMN IF NOT EXISTS jurisdiction_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS tax_type VARCHAR(40) NOT NULL DEFAULT 'VAT',
    ADD COLUMN IF NOT EXISTS inclusive BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS exemption_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS applies_to VARCHAR(40) NOT NULL DEFAULT 'INVOICE';

UPDATE finance_tax_rules
   SET jurisdiction_code = 'DEFAULT', active = FALSE
 WHERE jurisdiction_code IS NULL OR btrim(jurisdiction_code) = '';

ALTER TABLE finance_tax_rules
    ALTER COLUMN jurisdiction_code SET NOT NULL;

ALTER TABLE finance_tax_rules
    DROP CONSTRAINT IF EXISTS finance_tax_rules_tenant_id_code_key;
CREATE UNIQUE INDEX IF NOT EXISTS ux_finance_tax_rules_scope_effective
    ON finance_tax_rules(tenant_id, jurisdiction_code, code, valid_from);
CREATE INDEX IF NOT EXISTS ix_finance_tax_rules_jurisdiction_active
    ON finance_tax_rules(tenant_id, jurisdiction_code, active, valid_from, valid_until);

-- Multiple active tax jurisdictions are supported. Invoice numbering uses an
-- explicit tenant default jurisdiction/prefix, avoiding nondeterministic selection.
ALTER TABLE finance_tax_jurisdictions
    ADD COLUMN IF NOT EXISTS default_for_invoicing BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE finance_tax_jurisdictions j
   SET default_for_invoicing = TRUE
 WHERE j.id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY tenant_id
            ORDER BY active DESC, updated_at DESC NULLS LAST, created_at DESC NULLS LAST, id
        ) AS rn
          FROM finance_tax_jurisdictions
         WHERE active = TRUE
    ) ranked
    WHERE rn = 1
 );

DROP INDEX IF EXISTS ux_finance_tax_jurisdiction_active;
CREATE UNIQUE INDEX IF NOT EXISTS ux_finance_tax_jurisdiction_invoice_default
    ON finance_tax_jurisdictions(tenant_id) WHERE default_for_invoicing = TRUE;
ALTER TABLE finance_tax_jurisdictions
    DROP CONSTRAINT IF EXISTS ck_finance_tax_default_must_be_active;
ALTER TABLE finance_tax_jurisdictions
    ADD CONSTRAINT ck_finance_tax_default_must_be_active
    CHECK (NOT default_for_invoicing OR active);

-- Create an explicitly inactive placeholder for legacy, unspecific rules. The
-- operator must configure real jurisdiction and tax registration information.
INSERT INTO finance_tax_jurisdictions
    (id, tenant_id, jurisdiction_code, legal_name, country_code, currency, active)
SELECT gen_random_uuid(), q.tenant_id, 'DEFAULT',
       'Unconfigured tax jurisdiction - review required', 'ZZZ',
       COALESCE(q.currency, 'USD'), FALSE
  FROM (SELECT DISTINCT tenant_id, MAX(currency) AS currency
          FROM finance_tax_rules WHERE jurisdiction_code='DEFAULT' GROUP BY tenant_id) q
ON CONFLICT (tenant_id, jurisdiction_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS finance_tax_calculation_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    document_type VARCHAR(40) NOT NULL,
    document_id UUID NOT NULL,
    tax_rule_id UUID NOT NULL REFERENCES finance_tax_rules(id),
    jurisdiction_code VARCHAR(80) NOT NULL,
    tax_code VARCHAR(80) NOT NULL,
    tax_name VARCHAR(160) NOT NULL,
    effective_date DATE NOT NULL,
    inclusive BOOLEAN NOT NULL DEFAULT FALSE,
    taxable_amount NUMERIC(19,4) NOT NULL,
    rate NUMERIC(9,4) NOT NULL,
    tax_amount NUMERIC(19,4) NOT NULL,
    withholding_rate NUMERIC(9,4) NOT NULL DEFAULT 0,
    withholding_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_amount NUMERIC(19,4) NOT NULL,
    net_payable NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    rule_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_tax_snapshot_document
    ON finance_tax_calculation_snapshots(tenant_id, document_type, document_id, created_at DESC);

ALTER TABLE finance_notes
    ADD COLUMN IF NOT EXISTS tax_rate NUMERIC(9,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS tax_jurisdiction_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS tax_inclusive BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS finance_note_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    finance_note_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,
    old_values JSONB,
    new_values JSONB,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_finance_note_history
    ON finance_note_history(tenant_id, finance_note_id, changed_at DESC);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['finance_tax_calculation_snapshots', 'finance_note_history'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_%I ON %I', t, t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation_%I ON %I USING (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.current_tenant'', true), '''')::uuid)',
            t, t
        );
    END LOOP;
END $$;

-- finance_notes is a mutable workflow object only while DRAFT. Each change is
-- captured before it can be changed again; the history itself is append-only.
CREATE OR REPLACE FUNCTION public.capture_finance_note_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE v_tenant UUID; v_note UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Finance notes cannot be deleted; void draft notes instead';
    END IF;
    v_tenant := NEW.tenant_id;
    v_note := NEW.id;
    INSERT INTO finance_note_history(id, tenant_id, finance_note_id, action, old_values, new_values)
    VALUES (gen_random_uuid(), v_tenant, v_note, TG_OP,
            CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
            to_jsonb(NEW));
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_finance_notes_history ON finance_notes;
CREATE TRIGGER trg_finance_notes_history
AFTER INSERT OR UPDATE OR DELETE ON finance_notes
FOR EACH ROW EXECUTE FUNCTION public.capture_finance_note_history();

-- A posted/voided note is a legal financial record. It may be viewed forever,
-- but only DRAFT notes may transition; issued note values cannot be rewritten.
CREATE OR REPLACE FUNCTION public.prevent_posted_finance_note_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'A posted or voided finance note is immutable';
    END IF;
    IF NEW.status NOT IN ('DRAFT','APPROVED','VOID') THEN
        RAISE EXCEPTION 'Invalid finance-note status';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_posted_finance_note_mutation ON finance_notes;
CREATE TRIGGER trg_posted_finance_note_mutation
BEFORE UPDATE ON finance_notes
FOR EACH ROW EXECUTE FUNCTION public.prevent_posted_finance_note_mutation();

DROP TRIGGER IF EXISTS trg_finance_note_history_immutable ON finance_note_history;
CREATE TRIGGER trg_finance_note_history_immutable
BEFORE UPDATE OR DELETE ON finance_note_history
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

DROP TRIGGER IF EXISTS trg_tax_calculation_snapshots_immutable ON finance_tax_calculation_snapshots;
CREATE TRIGGER trg_tax_calculation_snapshots_immutable
BEFORE UPDATE OR DELETE ON finance_tax_calculation_snapshots
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

-- Financial posting entries and recorded payment receipts are append-only. A
-- correction must be posted as a compensating transaction, never by rewriting history.
DROP TRIGGER IF EXISTS trg_finance_ledger_entries_immutable ON finance_ledger_entries;
CREATE TRIGGER trg_finance_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON finance_ledger_entries
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

DROP TRIGGER IF EXISTS trg_commercial_payments_immutable ON commercial_payments;
CREATE TRIGGER trg_commercial_payments_immutable
BEFORE UPDATE OR DELETE ON commercial_payments
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();

-- Issued invoice identity, pricing and tax calculations cannot be silently
-- rewritten. Lifecycle, collections and note accumulators may still change.
CREATE OR REPLACE FUNCTION public.prevent_issued_invoice_financial_edits()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.lifecycle_status <> 'DRAFT' AND (
        NEW.invoice_no IS DISTINCT FROM OLD.invoice_no OR
        NEW.issue_date IS DISTINCT FROM OLD.issue_date OR
        NEW.client IS DISTINCT FROM OLD.client OR
        NEW.shipment_id IS DISTINCT FROM OLD.shipment_id OR
        NEW.currency IS DISTINCT FROM OLD.currency OR
        NEW.invoice_amount IS DISTINCT FROM OLD.invoice_amount OR
        NEW.subtotal_amount IS DISTINCT FROM OLD.subtotal_amount OR
        NEW.tax_rate IS DISTINCT FROM OLD.tax_rate OR
        NEW.tax_amount IS DISTINCT FROM OLD.tax_amount OR
        NEW.tax_code IS DISTINCT FROM OLD.tax_code OR
        NEW.tax_inclusive IS DISTINCT FROM OLD.tax_inclusive OR
        NEW.tax_jurisdiction_code IS DISTINCT FROM OLD.tax_jurisdiction_code OR
        NEW.withholding_amount IS DISTINCT FROM OLD.withholding_amount OR
        NEW.tax_legal_name_snapshot IS DISTINCT FROM OLD.tax_legal_name_snapshot OR
        NEW.tax_registration_snapshot IS DISTINCT FROM OLD.tax_registration_snapshot OR
        NEW.tax_address_snapshot IS DISTINCT FROM OLD.tax_address_snapshot OR
        NEW.tax_country_snapshot IS DISTINCT FROM OLD.tax_country_snapshot
    ) THEN
        RAISE EXCEPTION 'Issued invoice financial details are immutable; use a credit/debit note';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_issued_invoice_financial_edits ON commercial_invoices;
CREATE TRIGGER trg_issued_invoice_financial_edits
BEFORE UPDATE ON commercial_invoices
FOR EACH ROW EXECUTE FUNCTION public.prevent_issued_invoice_financial_edits();

CREATE OR REPLACE FUNCTION public.prevent_commercial_invoice_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Commercial invoices cannot be deleted; use the lifecycle void/cancel workflow';
END;
$$;
DROP TRIGGER IF EXISTS trg_commercial_invoice_no_delete ON commercial_invoices;
CREATE TRIGGER trg_commercial_invoice_no_delete
BEFORE DELETE ON commercial_invoices
FOR EACH ROW EXECUTE FUNCTION public.prevent_commercial_invoice_delete();
