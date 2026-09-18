-- Generalizes Shipment from road-only to multi-modal (air / sea / road / rail).
-- Mode-specific identifiers (AWB for air, BOL/container for sea) are stored in one
-- flexible carrier_reference_number column rather than a column per mode — a real
-- enterprise system eventually wants mode-specific validation (AWB format is 3-digit
-- airline prefix + 8 digits; container numbers follow ISO 6346 with a check digit),
-- but that validation belongs in the application layer per mode, not the schema.

ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS transport_mode VARCHAR(20) NOT NULL DEFAULT 'ROAD',
    ADD COLUMN IF NOT EXISTS carrier_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS carrier_reference_number VARCHAR(100); -- AWB / BOL / container number / etc.

ALTER TABLE shipments ALTER COLUMN transport_mode DROP DEFAULT;

-- The tracking timeline: the one thing that genuinely works the same way regardless
-- of mode. A shipment's story is a sequence of these events, oldest first.
CREATE TABLE shipment_tracking_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    event_type VARCHAR(50) NOT NULL, -- BOOKED, PICKED_UP, DEPARTED_ORIGIN, IN_TRANSIT,
                                      -- CUSTOMS_HOLD, ARRIVED_DESTINATION, OUT_FOR_DELIVERY,
                                      -- DELIVERED, EXCEPTION
    location VARCHAR(255),
    notes TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tracking_events_tenant_shipment ON shipment_tracking_events(tenant_id, shipment_id, occurred_at);

ALTER TABLE shipment_tracking_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipment_tracking_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_tracking_events ON shipment_tracking_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
