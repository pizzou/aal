-- Financial and operational integrity constraints.
ALTER TABLE air_cargo_flights ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE air_cargo_flights DROP CONSTRAINT IF EXISTS chk_air_flight_capacity;
ALTER TABLE air_cargo_flights ADD CONSTRAINT chk_air_flight_capacity
 CHECK (available_capacity_kg >= 0 AND available_capacity_kg <= total_capacity_kg);

ALTER TABLE air_cargo_bookings DROP CONSTRAINT IF EXISTS chk_air_booking_weight;
ALTER TABLE air_cargo_bookings ADD CONSTRAINT chk_air_booking_weight
 CHECK (requested_weight_kg > 0 AND (confirmed_weight_kg IS NULL OR confirmed_weight_kg > 0));

ALTER TABLE cargo_pieces DROP CONSTRAINT IF EXISTS chk_cargo_piece_dimensions;
ALTER TABLE cargo_pieces ADD CONSTRAINT chk_cargo_piece_dimensions
 CHECK (length_cm > 0 AND width_cm > 0 AND height_cm > 0 AND weight_kg > 0);

ALTER TABLE commercial_invoices DROP CONSTRAINT IF EXISTS chk_invoice_amounts;
ALTER TABLE commercial_invoices ADD CONSTRAINT chk_invoice_amounts
 CHECK (invoice_amount >= 0 AND amount_paid >= 0 AND amount_paid <= invoice_amount);

ALTER TABLE expense_records DROP CONSTRAINT IF EXISTS chk_expense_amount;
ALTER TABLE expense_records ADD CONSTRAINT chk_expense_amount CHECK (original_amount >= 0);

CREATE INDEX IF NOT EXISTS idx_air_booking_idempotency ON air_cargo_bookings(tenant_id,idempotency_key);
CREATE INDEX IF NOT EXISTS idx_air_flight_status_departure ON air_cargo_flights(tenant_id,status,departure_time);
CREATE INDEX IF NOT EXISTS idx_customs_status ON customs_declarations(tenant_id,status);
CREATE INDEX IF NOT EXISTS idx_awb_shipment ON awb_records(tenant_id,shipment_id);
CREATE INDEX IF NOT EXISTS idx_invoices_due ON commercial_invoices(tenant_id,due_date,amount_paid);
