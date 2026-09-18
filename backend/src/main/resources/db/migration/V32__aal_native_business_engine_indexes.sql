-- Native AAL operating model indexes. No spreadsheet structure is stored here.
CREATE INDEX IF NOT EXISTS ix_shipments_tenant_date_currency
    ON shipments (tenant_id, date_opened, currency);
CREATE INDEX IF NOT EXISTS ix_shipments_tenant_client_date
    ON shipments (tenant_id, client_name, date_opened);
CREATE INDEX IF NOT EXISTS ix_shipments_tenant_lane
    ON shipments (tenant_id, origin_city_port, destination_city_port);
CREATE INDEX IF NOT EXISTS ix_invoices_tenant_due_status
    ON commercial_invoices (tenant_id, due_date, amount_paid);
CREATE INDEX IF NOT EXISTS ix_tasks_tenant_due_status
    ON task_records (tenant_id, due_date, status);
CREATE INDEX IF NOT EXISTS ix_quotes_tenant_status_date
    ON commercial_quotes (tenant_id, status, quote_date);
