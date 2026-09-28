ALTER TABLE air_cargo_flights
    ADD COLUMN IF NOT EXISTS provider_reference VARCHAR(255),
    ADD COLUMN IF NOT EXISTS rate_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS rate_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS currency VARCHAR(10),
    ADD COLUMN IF NOT EXISTS total_price NUMERIC(18,3),
    ADD COLUMN IF NOT EXISTS bookable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS available_reason VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_air_flight_provider_reference
    ON air_cargo_flights(tenant_id, provider_reference);
