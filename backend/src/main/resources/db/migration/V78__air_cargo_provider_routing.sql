-- Preserve separate live offers from different airline/provider networks.
ALTER TABLE air_cargo_flights
    ADD COLUMN IF NOT EXISTS provider_code varchar(80);

UPDATE air_cargo_flights
SET provider_code = CASE
    WHEN COALESCE(NULLIF(provider_code, ''), NULLIF(source, ''), 'INTERNAL')
         IN ('INGESTED', 'EXTERNAL', 'AAL_PLANNING', 'INTERNAL', 'INTERNAL_CAPACITY') THEN 'INTERNAL'
    ELSE UPPER(COALESCE(NULLIF(provider_code, ''), NULLIF(source, ''), 'INTERNAL'))
END
WHERE provider_code IS NULL OR provider_code = '';

ALTER TABLE air_cargo_flights
    ALTER COLUMN provider_code SET DEFAULT 'INTERNAL';

ALTER TABLE air_cargo_flights
    ALTER COLUMN provider_code SET NOT NULL;

ALTER TABLE air_cargo_flights
    DROP CONSTRAINT IF EXISTS uk_air_flight;

ALTER TABLE air_cargo_flights
    ADD CONSTRAINT uk_air_flight
    UNIQUE (tenant_id, provider_code, carrier_code, flight_number, departure_time);

CREATE INDEX IF NOT EXISTS idx_air_cargo_flights_provider_route
    ON air_cargo_flights(tenant_id, provider_code, origin_code, destination_code, departure_time);
