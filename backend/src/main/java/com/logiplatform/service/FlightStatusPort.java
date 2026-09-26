package com.logiplatform.service;

import java.time.Instant;
import java.util.Optional;

public interface FlightStatusPort {
    Optional<FlightStatusResult> getStatus(String flightIataCode, String flightDate);

    record FlightStatusResult(
            String flightStatus,
            int departureDelayMinutes,
            int arrivalDelayMinutes,
            boolean significantDelay,
            Instant scheduledDeparture,
            Instant estimatedDeparture,
            Instant actualDeparture,
            Instant scheduledArrival,
            Instant estimatedArrival,
            Instant actualArrival,
            String providerEventId,
            String rawResponse
    ) {}
}
