-- AAL dashboard query performance indexes.
-- All indexes are tenant-leading so they remain compatible with RLS and the
-- application's explicit tenant predicates.

CREATE INDEX IF NOT EXISTS ix_expense_records_tenant_date_status
    ON expense_records (tenant_id, expense_date, status);

CREATE INDEX IF NOT EXISTS ix_logistics_exceptions_tenant_status_created
    ON logistics_exceptions (tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_task_records_tenant_due_status
    ON task_records (tenant_id, due_date, status);

CREATE INDEX IF NOT EXISTS ix_trip_shipments_tenant_shipment_trip
    ON trip_shipments (tenant_id, shipment_id, trip_id);
