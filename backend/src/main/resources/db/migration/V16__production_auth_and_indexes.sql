-- Production authentication hardening and high-volume query indexes.
ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_users_email_active ON users(email,active);

CREATE INDEX IF NOT EXISTS idx_tracking_events_tenant_shipment_time ON shipment_tracking_events(tenant_id,shipment_id,occurred_at);
CREATE INDEX IF NOT EXISTS idx_sensor_readings_tenant_shipment_time ON shipment_sensor_readings(tenant_id,shipment_id,recorded_at);
CREATE INDEX IF NOT EXISTS idx_gps_tenant_vehicle_time ON vehicle_gps_positions(tenant_id,vehicle_id,recorded_at);
CREATE INDEX IF NOT EXISTS idx_notifications_tenant_shipment_time ON notifications(tenant_id,shipment_id,created_at);
CREATE INDEX IF NOT EXISTS idx_rate_cards_tenant_mode ON rate_cards(tenant_id,transport_mode);
CREATE INDEX IF NOT EXISTS idx_accessorial_tenant_code ON accessorial_charges(tenant_id,code);
