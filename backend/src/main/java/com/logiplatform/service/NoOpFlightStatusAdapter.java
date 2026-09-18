package com.logiplatform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Active whenever no AviationStack API key is configured — the safe default. */
@Component
@ConditionalOnProperty(name = "flightstatus.aviationstack.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpFlightStatusAdapter implements FlightStatusPort {
    @Override
    public Optional<FlightStatusResult> getStatus(String flightIataCode, String flightDate) {
        return Optional.empty();
    }
}
