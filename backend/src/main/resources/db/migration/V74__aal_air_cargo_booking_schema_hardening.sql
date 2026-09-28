-- Harden airline booking persistence for databases created from older AAL schemas.
-- Booking requests may contain provider references/idempotency keys longer than
-- legacy VARCHAR(20) definitions. Widen all booking text columns used by the
-- current domain so a booking cannot fail with SQLState 22001.
ALTER TABLE air_cargo_bookings
    ALTER COLUMN carrier_code TYPE VARCHAR(80),
    ALTER COLUMN carrier_name TYPE VARCHAR(255),
    ALTER COLUMN flight_number TYPE VARCHAR(80),
    ALTER COLUMN origin_code TYPE VARCHAR(80),
    ALTER COLUMN destination_code TYPE VARCHAR(80),
    ALTER COLUMN status TYPE VARCHAR(80),
    ALTER COLUMN provider TYPE VARCHAR(255),
    ALTER COLUMN provider_reference TYPE VARCHAR(255),
    ALTER COLUMN confirmation_number TYPE VARCHAR(255),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255),
    ALTER COLUMN service_level TYPE VARCHAR(80),
    ALTER COLUMN last_operation_key TYPE VARCHAR(255),
    ALTER COLUMN last_provider_operation TYPE VARCHAR(255);

-- State-history values are application-controlled but must remain compatible
-- with the booking domain and correlation identifiers.
ALTER TABLE booking_state_transitions
    ALTER COLUMN from_state TYPE VARCHAR(80),
    ALTER COLUMN to_state TYPE VARCHAR(80),
    ALTER COLUMN reason TYPE VARCHAR(255),
    ALTER COLUMN correlation_id TYPE VARCHAR(160);

-- Integration attempts are written during external booking flows. Keep the
-- identifiers safely above provider-specific values.
ALTER TABLE integration_attempts
    ALTER COLUMN integration_code TYPE VARCHAR(120),
    ALTER COLUMN operation TYPE VARCHAR(120),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255),
    ALTER COLUMN status TYPE VARCHAR(40),
    ALTER COLUMN direction TYPE VARCHAR(32),
    ALTER COLUMN correlation_id TYPE VARCHAR(160);
