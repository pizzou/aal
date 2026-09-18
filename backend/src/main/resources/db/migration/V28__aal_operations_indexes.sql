CREATE INDEX IF NOT EXISTS idx_shipments_client_status ON shipments(tenant_id,client_name,status);
CREATE INDEX IF NOT EXISTS idx_shipments_payment ON shipments(tenant_id,payment_status,amount_billed_to_client);
CREATE INDEX IF NOT EXISTS idx_invoices_due_status ON commercial_invoices(tenant_id,due_date,amount_paid);
CREATE INDEX IF NOT EXISTS idx_quotes_status_followup ON commercial_quotes(tenant_id,status,follow_up_date);
CREATE INDEX IF NOT EXISTS idx_tasks_status_due ON task_records(tenant_id,status,due_date);
CREATE INDEX IF NOT EXISTS idx_expenses_date_category ON expense_records(tenant_id,expense_date,category);
