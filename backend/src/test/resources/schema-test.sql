DROP TABLE IF EXISTS accessorial_charges;
DROP TABLE IF EXISTS rate_cards;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS vehicle_gps_positions;
DROP TABLE IF EXISTS trip_shipments;
DROP TABLE IF EXISTS trips;
DROP TABLE IF EXISTS drivers;
DROP TABLE IF EXISTS vehicles;
DROP TABLE IF EXISTS shipment_tracking_events;
DROP TABLE IF EXISTS stock_movements;
DROP TABLE IF EXISTS inventory_items;
DROP TABLE IF EXISTS warehouses;
DROP TABLE IF EXISTS shipments;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS tenants;

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'ADMIN',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    reference_code VARCHAR(100) NOT NULL,
    origin_address VARCHAR(1000) NOT NULL,
    destination_address VARCHAR(1000) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    transport_mode VARCHAR(20) NOT NULL DEFAULT 'ROAD',
    carrier_name VARCHAR(255),
    carrier_reference_number VARCHAR(100),
    tracking_token UUID NOT NULL UNIQUE,
    weight_kg INT,
    notification_email VARCHAR(255),
    flight_number VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE shipment_tracking_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    location VARCHAR(255),
    notes VARCHAR(2000),
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE warehouses (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE inventory_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    quantity_on_hand INT NOT NULL DEFAULT 0,
    reorder_level INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE stock_movements (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    inventory_item_id UUID NOT NULL,
    movement_type VARCHAR(20) NOT NULL,
    quantity INT NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    registration_number VARCHAR(50) NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL,
    capacity_kg INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE drivers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    license_number VARCHAR(100) NOT NULL,
    phone VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE trips (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    driver_id UUID NOT NULL,
    origin_address VARCHAR(1000) NOT NULL,
    destination_address VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    scheduled_departure TIMESTAMP,
    actual_departure TIMESTAMP,
    actual_arrival TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE trip_shipments (
    trip_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    PRIMARY KEY (trip_id, shipment_id)
);

CREATE TABLE vehicle_gps_positions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    vehicle_id UUID NOT NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    speed_kmh DOUBLE,
    heading_degrees DOUBLE,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE shipment_sensor_readings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    temperature_celsius DOUBLE,
    humidity_percent DOUBLE,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE shipment_sensor_thresholds (
    shipment_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    min_temperature_celsius DOUBLE,
    max_temperature_celsius DOUBLE,
    min_humidity_percent DOUBLE,
    max_humidity_percent DOUBLE
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    shipment_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    recipient VARCHAR(255),
    subject VARCHAR(500) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_detail VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE rate_cards (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    transport_mode VARCHAR(20) NOT NULL,
    base_rate_per_kg DECIMAL(10,2) NOT NULL,
    min_charge DECIMAL(10,2) NOT NULL DEFAULT 0,
    fuel_surcharge_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE accessorial_charges (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    description VARCHAR(255) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD'
);
