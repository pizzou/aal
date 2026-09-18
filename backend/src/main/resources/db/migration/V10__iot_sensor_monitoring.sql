-- Cold-chain / condition monitoring for sensitive or perishable cargo. Same verified
-- pattern as vehicle_gps_positions (Postgres for durable history, Redis for latest
-- reading) — see RLS_VERIFICATION.md for why that split works and what it doesn't
-- protect against (Redis unavailability, which falls back to Postgres).
--
-- Tied to SHIPMENTS, not vehicles: a sensor monitors the cargo itself, which may
-- move across multiple vehicles/legs in a multi-modal journey (truck -> plane ->
-- truck) while the sensor keeps reporting on the same physical shipment throughout.

CREATE TABLE shipment_sensor_readings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    temperature_celsius DOUBLE PRECISION,
    humidity_percent DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_humidity_range CHECK (humidity_percent IS NULL OR humidity_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_at_least_one_reading CHECK (temperature_celsius IS NOT NULL OR humidity_percent IS NOT NULL)
);

-- Per-shipment thresholds: perishables (e.g. -18C for frozen) vs. general cargo have
-- very different acceptable ranges, so this can't be a single global constant.
CREATE TABLE shipment_sensor_thresholds (
    shipment_id UUID PRIMARY KEY REFERENCES shipments(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    min_temperature_celsius DOUBLE PRECISION,
    max_temperature_celsius DOUBLE PRECISION,
    min_humidity_percent DOUBLE PRECISION,
    max_humidity_percent DOUBLE PRECISION
);

CREATE INDEX idx_sensor_readings_tenant_shipment ON shipment_sensor_readings(tenant_id, shipment_id, recorded_at DESC);

ALTER TABLE shipment_sensor_readings ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_sensor_readings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_sensor_readings ON shipment_sensor_readings
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE shipment_sensor_thresholds ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_sensor_thresholds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_sensor_thresholds ON shipment_sensor_thresholds
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
