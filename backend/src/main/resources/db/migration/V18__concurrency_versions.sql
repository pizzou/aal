ALTER TABLE shipments ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE air_cargo_bookings ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE commercial_invoices ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_shipments_tenant_updated ON shipments(tenant_id,updated_at);
CREATE INDEX IF NOT EXISTS idx_air_bookings_tenant_updated ON air_cargo_bookings(tenant_id,updated_at);
CREATE INDEX IF NOT EXISTS idx_invoices_tenant_updated ON commercial_invoices(tenant_id,issue_date);
