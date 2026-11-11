-- Rwanda statutory invoice categories. These are selectable tax treatments,
-- not competing default rates. Prior issued invoices and their snapshots stay intact.
ALTER TABLE commercial_invoices
    ADD COLUMN IF NOT EXISTS tax_basis_reference VARCHAR(255);

-- VAT registration is verified once by Finance/Admin, not selected per invoice.
-- Unknown businesses must not be assumed to be VAT-registered.
ALTER TABLE finance_tax_jurisdictions
    ADD COLUMN IF NOT EXISTS vat_registration_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN IF NOT EXISTS vat_status_verified_at TIMESTAMPTZ;
ALTER TABLE finance_tax_jurisdictions
    ADD CONSTRAINT ck_finance_jurisdiction_vat_registration_status
    CHECK (vat_registration_status IN ('UNVERIFIED','REGISTERED','NOT_REGISTERED'));

-- AAL uses existing verified Rwanda legal identities; do not fabricate a TIN
-- or create a default company for an unconfigured tenant.
INSERT INTO finance_tax_rules
    (id, tenant_id, code, tax_name, rate, withholding_rate, currency,
     valid_from, valid_until, active, jurisdiction_code, tax_type,
     inclusive, exemption_code, applies_to)
SELECT gen_random_uuid(), j.tenant_id, p.code, p.tax_name, p.rate,
       0.0000, NULL, DATE '2023-09-14', NULL, TRUE,
       j.jurisdiction_code, CASE WHEN p.code='RRA-RW-NONVAT' THEN 'OTHER' ELSE 'VAT' END, FALSE, p.exemption_code, 'INVOICE'
FROM finance_tax_jurisdictions j
CROSS JOIN (VALUES
    ('RRA-RW-VAT18','RRA standard VAT 18%',18.0000::numeric,NULL::varchar),
    ('RRA-RW-ZERO-EXPORT','RRA zero-rated eligible exports',0.0000::numeric,'RRA_EXPORT'),
    ('RRA-RW-ZERO-TRANSIT','RRA zero-rated eligible inbound/transit freight',0.0000::numeric,'RRA_INBOUND_TRANSIT'),
    ('RRA-RW-EXEMPT','RRA VAT-exempt supply',0.0000::numeric,'RRA_EXEMPT'),
    ('RRA-RW-NONVAT','No VAT (not VAT registered)',0.0000::numeric,'RRA_NONVAT')
) AS p(code,tax_name,rate,exemption_code)
WHERE upper(j.country_code)='RW'
  AND j.active=TRUE
ON CONFLICT (tenant_id, jurisdiction_code, code, valid_from) DO NOTHING;

-- Legal basis evidence is part of the issued document's immutable tax record.
CREATE OR REPLACE FUNCTION public.prevent_issued_invoice_tax_basis_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.lifecycle_status <> 'DRAFT'
     AND NEW.tax_basis_reference IS DISTINCT FROM OLD.tax_basis_reference THEN
    RAISE EXCEPTION 'Issued invoice tax-basis evidence is immutable; issue a correction document';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_issued_invoice_tax_basis_change ON commercial_invoices;
CREATE TRIGGER trg_issued_invoice_tax_basis_change
BEFORE UPDATE ON commercial_invoices
FOR EACH ROW EXECUTE FUNCTION public.prevent_issued_invoice_tax_basis_change();
