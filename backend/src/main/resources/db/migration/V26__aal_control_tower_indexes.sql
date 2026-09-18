-- Control-tower query indexes.
-- These are intentionally tenant-leading so they remain useful with RLS and
-- the application's explicit tenant predicates.

CREATE INDEX IF NOT EXISTS idx_shipments_tenant_date_opened
    ON shipments (tenant_id, date_opened);

CREATE INDEX IF NOT EXISTS idx_shipments_tenant_etd
    ON shipments (tenant_id, etd);

CREATE INDEX IF NOT EXISTS idx_shipments_tenant_eta_status
    ON shipments (tenant_id, eta, status);

CREATE INDEX IF NOT EXISTS idx_shipments_tenant_mode
    ON shipments (tenant_id, transport_mode);

CREATE INDEX IF NOT EXISTS idx_shipments_tenant_currency
    ON shipments (tenant_id, currency);

CREATE INDEX IF NOT EXISTS idx_trips_tenant_scheduled_departure
    ON trips (tenant_id, scheduled_departure);

CREATE INDEX IF NOT EXISTS idx_trip_shipments_tenant_shipment
    ON trip_shipments (tenant_id, shipment_id);

CREATE INDEX IF NOT EXISTS idx_logistics_tasks_tenant_status_due
    ON logistics_tasks (tenant_id, status, due_at);
