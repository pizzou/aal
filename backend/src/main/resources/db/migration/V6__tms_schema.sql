CREATE TABLE vehicles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    registration_number VARCHAR(50) NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL, -- TRUCK, VAN, MOTORCYCLE
    capacity_kg INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, ON_TRIP, MAINTENANCE, OUT_OF_SERVICE
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, registration_number)
);

CREATE TABLE drivers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    full_name VARCHAR(255) NOT NULL,
    license_number VARCHAR(100) NOT NULL,
    phone VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, ON_TRIP, OFF_DUTY
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, license_number)
);

CREATE TABLE trips (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    vehicle_id UUID NOT NULL REFERENCES vehicles(id),
    driver_id UUID NOT NULL REFERENCES drivers(id),
    origin_address TEXT NOT NULL,
    destination_address TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED', -- PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
    scheduled_departure TIMESTAMPTZ,
    actual_departure TIMESTAMPTZ,
    actual_arrival TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Many-to-many: a trip can carry several shipments; a shipment moves on exactly one
-- active trip leg at a time in this simple model (a real system would model legs
-- separately for multi-hop journeys — deliberately out of scope here, same reasoning
-- as deferring full route optimization earlier).
CREATE TABLE trip_shipments (
    trip_id UUID NOT NULL REFERENCES trips(id),
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    PRIMARY KEY (trip_id, shipment_id)
);

CREATE INDEX idx_vehicles_tenant ON vehicles(tenant_id);
CREATE INDEX idx_drivers_tenant ON drivers(tenant_id);
CREATE INDEX idx_trips_tenant ON trips(tenant_id);
CREATE INDEX idx_trips_tenant_status ON trips(tenant_id, status);
CREATE INDEX idx_trip_shipments_tenant ON trip_shipments(tenant_id);
