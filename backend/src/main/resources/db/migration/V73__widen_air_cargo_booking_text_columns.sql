-- Existing production databases may have older VARCHAR(20) definitions for
-- airline booking fields. The current domain model permits longer provider
-- states/references and must not fail booking creation with SQLState 22001.
ALTER TABLE air_cargo_bookings
    ALTER COLUMN carrier_code TYPE VARCHAR(80),
    ALTER COLUMN flight_number TYPE VARCHAR(80),
    ALTER COLUMN status TYPE VARCHAR(80),
    ALTER COLUMN provider TYPE VARCHAR(255),
    ALTER COLUMN provider_reference TYPE VARCHAR(255),
    ALTER COLUMN confirmation_number TYPE VARCHAR(255),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255),
    ALTER COLUMN service_level TYPE VARCHAR(80),
    ALTER COLUMN last_operation_key TYPE VARCHAR(255),
    ALTER COLUMN last_provider_operation TYPE VARCHAR(255);
