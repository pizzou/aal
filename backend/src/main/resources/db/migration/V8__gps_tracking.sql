-- Design note: this is a REST + Redis ingestion path, not the Kafka streaming
-- pipeline discussed earlier for thousands-of-vehicles scale. That decision is
-- deliberate: Kafka can't be verified in the sandbox this was built in (no route to
-- pull its images or a broker), and shipping unverified streaming code in a build
-- that has otherwise insisted on real verification would be exactly the inconsistency
-- to avoid. This table + a Redis latest-position cache is genuinely tested (see
-- RLS_VERIFICATION.md) and is a reasonable path for low-to-moderate fleet sizes.
-- Swap the ingestion endpoint's internals for a Kafka consumer later without changing
-- this table's shape if/when volume actually requires it — that migration path was
-- part of the original architecture discussion.

CREATE TABLE vehicle_gps_positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    vehicle_id UUID NOT NULL REFERENCES vehicles(id),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    speed_kmh DOUBLE PRECISION,
    heading_degrees DOUBLE PRECISION,
    recorded_at TIMESTAMPTZ NOT NULL, -- when the device took the reading
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), -- when the server received it
    CONSTRAINT chk_lat_range CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_lng_range CHECK (longitude BETWEEN -180 AND 180)
);

-- Queried for history/playback by vehicle and time range — this index shape matches that access pattern.
CREATE INDEX idx_gps_tenant_vehicle_time ON vehicle_gps_positions(tenant_id, vehicle_id, recorded_at DESC);

ALTER TABLE vehicle_gps_positions ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_gps_positions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_gps_positions ON vehicle_gps_positions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
