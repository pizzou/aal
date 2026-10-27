-- Separate supplier cost accruals from actual cash paid. Never reinterpret the
-- older shipment SUPPLIER_PAYMENT postings as supplier invoices.
CREATE TABLE supplier_bills (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 shipment_id UUID NOT NULL REFERENCES shipments(id),
 supplier_name VARCHAR(200) NOT NULL,
 supplier_key VARCHAR(200) NOT NULL,
 vendor_invoice_reference VARCHAR(120) NOT NULL,
 currency VARCHAR(10) NOT NULL,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 amount_paid NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (amount_paid >= 0 AND amount_paid <= amount),
 bill_date DATE NOT NULL,
 due_date DATE,
 description TEXT,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED')),
 created_by UUID NOT NULL REFERENCES users(id),
 approved_by UUID REFERENCES users(id),
 approved_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id, supplier_key, vendor_invoice_reference),
 CHECK ((status='DRAFT' AND approved_by IS NULL AND approved_at IS NULL) OR
        (status='APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
CREATE INDEX ix_supplier_bills_shipment ON supplier_bills (tenant_id,shipment_id);
CREATE INDEX ix_supplier_bills_tenant_status_due ON supplier_bills (tenant_id,status,due_date);

CREATE TABLE supplier_bill_payments (
 id UUID PRIMARY KEY,
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 supplier_bill_id UUID NOT NULL REFERENCES supplier_bills(id) ON DELETE RESTRICT,
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
 currency VARCHAR(10) NOT NULL,
 payment_method VARCHAR(40) NOT NULL,
 payment_reference VARCHAR(200) NOT NULL,
 idempotency_key VARCHAR(160) NOT NULL,
 recorded_by UUID NOT NULL REFERENCES users(id),
 paid_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,idempotency_key)
);
CREATE INDEX ix_supplier_payments_bill ON supplier_bill_payments (tenant_id,supplier_bill_id,paid_at);

ALTER TABLE supplier_bills ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_bills FORCE ROW LEVEL SECURITY;
CREATE POLICY supplier_bills_tenant ON supplier_bills
 USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
ALTER TABLE supplier_bill_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_bill_payments FORCE ROW LEVEL SECURITY;
CREATE POLICY supplier_bill_payments_tenant ON supplier_bill_payments
 USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Financial receipts cannot be rewritten or removed. Adjustments require a
-- separately authorized correction/reversal workflow, not SQL updates.
CREATE TRIGGER trg_supplier_bill_payments_immutable
 BEFORE UPDATE OR DELETE ON supplier_bill_payments
 FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();
-- No modifications to approved vendor cost, currency, ownership or shipment.
CREATE OR REPLACE FUNCTION public.guard_approved_supplier_bill() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status = 'APPROVED' AND (
   NEW.shipment_id IS DISTINCT FROM OLD.shipment_id OR
   NEW.supplier_name IS DISTINCT FROM OLD.supplier_name OR
   NEW.supplier_key IS DISTINCT FROM OLD.supplier_key OR
   NEW.vendor_invoice_reference IS DISTINCT FROM OLD.vendor_invoice_reference OR
   NEW.currency IS DISTINCT FROM OLD.currency OR
   NEW.amount IS DISTINCT FROM OLD.amount OR
   NEW.bill_date IS DISTINCT FROM OLD.bill_date OR
   NEW.due_date IS DISTINCT FROM OLD.due_date OR
   NEW.description IS DISTINCT FROM OLD.description OR
   NEW.status IS DISTINCT FROM OLD.status OR
   NEW.created_by IS DISTINCT FROM OLD.created_by OR
   NEW.approved_by IS DISTINCT FROM OLD.approved_by OR
   NEW.approved_at IS DISTINCT FROM OLD.approved_at
 ) THEN
    RAISE EXCEPTION 'Approved supplier bills are immutable apart from settlement total';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER trg_guard_approved_supplier_bill BEFORE UPDATE ON supplier_bills
 FOR EACH ROW EXECUTE FUNCTION public.guard_approved_supplier_bill();

-- Keep customer cash receipts classified without guessing old payment methods.
ALTER TABLE commercial_payments
 ADD COLUMN IF NOT EXISTS payment_method VARCHAR(40) NOT NULL DEFAULT 'UNSPECIFIED';
