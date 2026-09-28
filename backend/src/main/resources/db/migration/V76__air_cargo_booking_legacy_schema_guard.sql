-- Keep the booking write path compatible with older AAL production databases.
-- V13 originally created shipments.flight_number as VARCHAR(20). The current
-- air-cargo domain may receive longer legacy values, so normalize in Java and
-- widen the persisted column as a second line of defence.
ALTER TABLE shipments
    ALTER COLUMN flight_number TYPE VARCHAR(80);

-- Older deployments may also have retained short integration-alert status
-- columns. Booking creation can create/resolve integration alerts in related
-- flows, so keep that table compatible with the current status vocabulary.
ALTER TABLE airline_integration_alerts
    ALTER COLUMN status TYPE VARCHAR(80);

-- Booking tables were widened by V73/V74, but these statements are harmless
-- on databases that already have the wider definitions and protect upgrades
-- from older schemas that skipped those definitions.
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
    ALTER COLUMN service_level TYPE VARCHAR(80),
    ALTER COLUMN last_operation_key TYPE VARCHAR(255),
    ALTER COLUMN last_provider_operation TYPE VARCHAR(255),
    ALTER COLUMN idempotency_key TYPE VARCHAR(255);
