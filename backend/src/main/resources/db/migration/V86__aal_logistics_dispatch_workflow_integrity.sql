-- Logistics execution integrity: preserve separate stop-arrival/departure facts,
-- permit repeat failed delivery attempts but only one successful POD per shipment,
-- and enforce tenant-consistent operational references for all new writes.

ALTER TABLE dispatch_stops
    ADD COLUMN IF NOT EXISTS arrived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS departed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Historical dispatch rows only stored one actual_at value. Seed arrival time
-- from that legacy value when the row has already arrived/completed; do not guess
-- a departure time from a single timestamp.
UPDATE dispatch_stops
   SET arrived_at = actual_at
 WHERE arrived_at IS NULL
   AND actual_at IS NOT NULL
   AND status IN ('ARRIVED', 'COMPLETED');

-- Older versions accidentally made all delivery attempts unique per shipment.
-- Remove those constraints, then enforce uniqueness only for successful evidence.
ALTER TABLE proof_of_delivery DROP CONSTRAINT IF EXISTS proof_of_delivery_tenant_id_shipment_id_key;
ALTER TABLE proof_of_delivery DROP CONSTRAINT IF EXISTS uk_pod_shipment;
CREATE UNIQUE INDEX IF NOT EXISTS ux_pod_one_success_per_shipment
    ON proof_of_delivery (tenant_id, shipment_id)
    WHERE failure_reason IS NULL OR btrim(failure_reason) = '';
CREATE INDEX IF NOT EXISTS ix_pod_attempt_history
    ON proof_of_delivery (tenant_id, shipment_id, delivered_at DESC);
CREATE INDEX IF NOT EXISTS ix_trip_shipments_tenant_shipment_trip
    ON trip_shipments (tenant_id, shipment_id, trip_id);

-- Add matching tenant-key unique constraints so composite foreign keys can
-- prevent cross-tenant references even if an application check is bypassed.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_shipments_id_tenant') THEN
        ALTER TABLE shipments ADD CONSTRAINT uq_shipments_id_tenant UNIQUE (id, tenant_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_trips_id_tenant') THEN
        ALTER TABLE trips ADD CONSTRAINT uq_trips_id_tenant UNIQUE (id, tenant_id);
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_trip_shipments_trip_tenant') THEN
        ALTER TABLE trip_shipments ADD CONSTRAINT fk_trip_shipments_trip_tenant
            FOREIGN KEY (trip_id, tenant_id) REFERENCES trips (id, tenant_id) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_trip_shipments_shipment_tenant') THEN
        ALTER TABLE trip_shipments ADD CONSTRAINT fk_trip_shipments_shipment_tenant
            FOREIGN KEY (shipment_id, tenant_id) REFERENCES shipments (id, tenant_id) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_dispatch_stops_trip_tenant') THEN
        ALTER TABLE dispatch_stops ADD CONSTRAINT fk_dispatch_stops_trip_tenant
            FOREIGN KEY (trip_id, tenant_id) REFERENCES trips (id, tenant_id) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_dispatch_stops_shipment_tenant') THEN
        ALTER TABLE dispatch_stops ADD CONSTRAINT fk_dispatch_stops_shipment_tenant
            FOREIGN KEY (shipment_id, tenant_id) REFERENCES shipments (id, tenant_id) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pod_shipment_tenant') THEN
        ALTER TABLE proof_of_delivery ADD CONSTRAINT fk_pod_shipment_tenant
            FOREIGN KEY (shipment_id, tenant_id) REFERENCES shipments (id, tenant_id) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pod_trip_tenant') THEN
        ALTER TABLE proof_of_delivery ADD CONSTRAINT fk_pod_trip_tenant
            FOREIGN KEY (trip_id, tenant_id) REFERENCES trips (id, tenant_id) NOT VALID;
    END IF;
END $$;

-- Tracking events and delivery evidence are append-only operational records.
CREATE OR REPLACE FUNCTION aal_reject_logistics_evidence_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; create a correcting operational event instead', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_tracking_events_append_only ON shipment_tracking_events;
CREATE TRIGGER trg_tracking_events_append_only
    BEFORE UPDATE OR DELETE ON shipment_tracking_events
    FOR EACH ROW EXECUTE FUNCTION aal_reject_logistics_evidence_mutation();

DROP TRIGGER IF EXISTS trg_pod_evidence_append_only ON proof_of_delivery;
CREATE TRIGGER trg_pod_evidence_append_only
    BEFORE UPDATE OR DELETE ON proof_of_delivery
    FOR EACH ROW EXECUTE FUNCTION aal_reject_logistics_evidence_mutation();

-- Validate new dispatch measurements at the database boundary. NOT VALID avoids
-- making an initial rollout fail on old data; the checks still apply to new rows.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dispatch_stop_coordinates') THEN
        ALTER TABLE dispatch_stops ADD CONSTRAINT ck_dispatch_stop_coordinates
            CHECK ((latitude IS NULL AND longitude IS NULL)
                OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dispatch_stop_distance_nonnegative') THEN
        ALTER TABLE dispatch_stops ADD CONSTRAINT ck_dispatch_stop_distance_nonnegative
            CHECK (distance_from_previous_km IS NULL OR distance_from_previous_km >= 0) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dispatch_stop_duration_nonnegative') THEN
        ALTER TABLE dispatch_stops ADD CONSTRAINT ck_dispatch_stop_duration_nonnegative
            CHECK (planned_duration_minutes IS NULL OR planned_duration_minutes >= 0) NOT VALID;
    END IF;
END $$;
