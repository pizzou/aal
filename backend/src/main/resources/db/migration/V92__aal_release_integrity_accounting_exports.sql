-- AAL financial approval evidence and immutable, downloadable GL CSV exports.
-- Historical migrations are deliberately not edited.
ALTER TABLE carrier_settlements ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id);
CREATE INDEX IF NOT EXISTS ix_carrier_settlements_maker_checker
    ON carrier_settlements(tenant_id,status,created_by,approved_by);
ALTER TABLE accounting_exports ADD COLUMN IF NOT EXISTS payload_csv TEXT;
COMMENT ON COLUMN accounting_exports.payload_csv IS
    'Exact period-scoped CSV bytes (UTF-8) corresponding to payload_hash; no placeholder metadata export.';

-- HMAC verified external authority events; never accept a staff-side release.
CREATE TABLE IF NOT EXISTS customs_authority_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_id VARCHAR(128) NOT NULL,
    declaration_id UUID NOT NULL REFERENCES customs_declarations(id),
    provider_code VARCHAR(64) NOT NULL,
    event_type VARCHAR(30) NOT NULL CHECK(event_type IN ('SUBMISSION_ACK','RELEASED','HOLD','REJECTED')),
    external_reference VARCHAR(255) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(tenant_id,event_id)
);
CREATE INDEX IF NOT EXISTS ix_customs_authority_events_declaration
    ON customs_authority_events(tenant_id,declaration_id,received_at DESC);
ALTER TABLE customs_authority_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE customs_authority_events FORCE ROW LEVEL SECURITY;
CREATE POLICY customs_authority_events_tenant ON customs_authority_events
USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Preserve signed authority evidence; corrections must arrive as new provider events.
CREATE TRIGGER trg_customs_authority_events_immutable
BEFORE UPDATE OR DELETE ON customs_authority_events
FOR EACH ROW EXECUTE FUNCTION public.prevent_immutable_financial_document_change();


-- Enforce financial period closure at the database boundary, not only in controllers.
-- Sharing period row locks serializes financial posting against period closure.
CREATE OR REPLACE FUNCTION public.prevent_posting_to_closed_period()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    financial_period_status TEXT;
BEGIN
    FOR financial_period_status IN
        SELECT status FROM finance_periods
        WHERE tenant_id = NEW.tenant_id
          AND (NEW.posted_at AT TIME ZONE 'Africa/Kigali')::date BETWEEN period_start AND period_end
        FOR SHARE
    LOOP
        IF financial_period_status = 'CLOSED' THEN
            RAISE EXCEPTION 'Cannot post finance ledger entry to a CLOSED financial period'
              USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reject_closed_period_ledger_posts
BEFORE INSERT ON finance_ledger_entries
FOR EACH ROW EXECUTE FUNCTION public.prevent_posting_to_closed_period();
