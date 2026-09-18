ALTER TABLE shipment_sensor_readings ADD COLUMN IF NOT EXISTS event_id UUID;
UPDATE shipment_sensor_readings SET event_id=id WHERE event_id IS NULL;
ALTER TABLE shipment_sensor_readings ALTER COLUMN event_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sensor_reading_event ON shipment_sensor_readings(tenant_id,event_id);
