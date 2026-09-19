-- High-volume shipment register search support.
-- The UI now performs server-side filtering instead of loading an arbitrary
-- first page into the browser. pg_trgm accelerates case-insensitive operator
-- search as the shipment register grows.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS ix_shipments_reference_trgm
    ON shipments USING gin (LOWER(reference_code) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_client_trgm
    ON shipments USING gin (LOWER(client_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_commodity_trgm
    ON shipments USING gin (LOWER(commodity) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_contact_trgm
    ON shipments USING gin (LOWER(contact) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_carrier_trgm
    ON shipments USING gin (LOWER(carrier_name) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_airline_trgm
    ON shipments USING gin (LOWER(airline_used) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_invoice_trgm
    ON shipments USING gin (LOWER(invoice_no) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_origin_trgm
    ON shipments USING gin (LOWER(origin_city_port) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_destination_trgm
    ON shipments USING gin (LOWER(destination_city_port) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_shipments_tenant_status_mode_date
    ON shipments (tenant_id, status, transport_mode, date_opened DESC);
