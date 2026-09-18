-- notification_email is nullable and separate from any structured contact model
-- (same honest gap as shipper/consignee in the AWB feature: this platform doesn't
-- yet have a Party/contact domain model, just an email string here). No email set
-- means notifications are still logged for audit purposes, just never sent anywhere.
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS notification_email VARCHAR(255);

-- Every notification attempt is recorded here — sent, logged-only (no email
-- configured), or failed — so there's always an audit trail of what would have
-- gone out, even in environments with no real SMTP configured (the default).
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    recipient VARCHAR(255),
    subject VARCHAR(500) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL, -- SENT, LOGGED_ONLY, FAILED
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_tenant_shipment ON notifications(tenant_id, shipment_id, created_at DESC);

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_notifications ON notifications
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
