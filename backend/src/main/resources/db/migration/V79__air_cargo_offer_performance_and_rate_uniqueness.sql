-- Live provider search returns multiple rate offers for the same flight.
-- Preserve them as separate auditable offers and index the route-search paths.
ALTER TABLE air_cargo_flights
    DROP CONSTRAINT IF EXISTS uk_air_flight;

ALTER TABLE air_cargo_flights
    ADD CONSTRAINT uk_air_flight
    UNIQUE (tenant_id, provider_code, carrier_code, flight_number, departure_time, rate_id);

CREATE INDEX IF NOT EXISTS idx_air_cargo_flights_route_search
    ON air_cargo_flights(tenant_id, origin_code, destination_code, departure_time);

CREATE INDEX IF NOT EXISTS idx_air_cargo_flights_connection_search
    ON air_cargo_flights(tenant_id, origin_code, departure_time);

CREATE INDEX IF NOT EXISTS idx_air_cargo_flights_provider_reference
    ON air_cargo_flights(tenant_id, provider_code, provider_reference);
