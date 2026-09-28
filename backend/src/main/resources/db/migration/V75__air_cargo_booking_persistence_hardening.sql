-- Final persistence hardening for airline booking creation.
-- Older AAL production schemas may still contain VARCHAR(20) columns even when
-- the application model and earlier migrations expect larger values.

ALTER TABLE IF EXISTS air_cargo_bookings
    ALTER COLUMN carrier_code TYPE VARCHAR(80),
    ALTER COLUMN carrier_name TYPE VARCHAR(255),
    ALTER COLUMN flight_number TYPE VARCHAR(80),
    ALTER COLUMN origin_code TYPE VARCHAR(80),
    ALTER COLUMN destination_code TYPE VARCHAR(80),
    ALTER COLUMN status TYPE VARCHAR(80),
    ALTER COLUMN provider TYPE VARCHAR(255),
    ALTER COLUMN provider_reference TYPE VARCHAR(255),
    ALTER COLUMN confirmation_number TYPE VARCHAR(255),
    ALTER COLUMN service_level TYPE VARCHAR(80),
    ALTER COLUMN last_operation_key TYPE VARCHAR(255),
    ALTER COLUMN last_provider_operation TYPE VARCHAR(255),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255);

ALTER TABLE IF EXISTS booking_state_transitions
    ALTER COLUMN from_state TYPE VARCHAR(80),
    ALTER COLUMN to_state TYPE VARCHAR(80),
    ALTER COLUMN reason TYPE VARCHAR(255),
    ALTER COLUMN correlation_id TYPE VARCHAR(160);

ALTER TABLE IF EXISTS integration_attempts
    ALTER COLUMN integration_code TYPE VARCHAR(120),
    ALTER COLUMN operation TYPE VARCHAR(120),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255),
    ALTER COLUMN status TYPE VARCHAR(40),
    ALTER COLUMN direction TYPE VARCHAR(32),
    ALTER COLUMN correlation_id TYPE VARCHAR(160);

ALTER TABLE IF EXISTS airline_integration_dead_letters
    ALTER COLUMN provider TYPE VARCHAR(120),
    ALTER COLUMN operation TYPE VARCHAR(120),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255),
    ALTER COLUMN correlation_id TYPE VARCHAR(160),
    ALTER COLUMN status TYPE VARCHAR(40);
