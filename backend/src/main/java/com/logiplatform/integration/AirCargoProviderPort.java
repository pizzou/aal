package com.logiplatform.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Normalized contract between AAL and an airline/cargo provider.
 * A real airline adapter implements this contract; the core booking domain does
 * not depend on an airline's HTTP/XML/ONE Record details.
 */
public interface AirCargoProviderPort {
    String providerCode();

    ProviderCapabilities capabilities();

    List<FlightOffer> searchFlights(String origin, String destination, Instant from, Instant to, BigDecimal weightKg);

    CapacitySnapshot capacity(String flightNumber, Instant date);

    BookingResult book(BookingCommand command);

    BookingResult getBooking(String providerReference);

    BookingResult amend(AmendmentCommand command);

    BookingResult cancel(CancellationCommand command);

    FlightStatus getFlightStatus(String flightNumber, String flightDate);

    AwbSubmissionResult submitAwb(Map<String, Object> payload, String idempotencyKey);

    record ProviderCapabilities(
            boolean scheduleSearch,
            boolean liveCapacity,
            boolean booking,
            boolean amendment,
            boolean cancellation,
            boolean flightStatus,
            boolean awbSubmission,
            boolean webhooks,
            boolean oauth2,
            boolean apiKey,
            List<String> standards) {}

    record CapacitySnapshot(String flightNumber, BigDecimal availableCapacityKg, BigDecimal totalCapacityKg, Instant asOf, String source) {}

    record FlightOffer(
            String carrierCode,
            String carrierName,
            String flightNumber,
            String origin,
            String destination,
            Instant departure,
            Instant arrival,
            BigDecimal totalCapacityKg,
            BigDecimal availableCapacityKg,
            String serviceLevel,
            String status,
            String providerReference) {}

    record BookingCommand(
            String idempotencyKey,
            String shipmentId,
            String carrierCode,
            String carrierName,
            String flightNumber,
            Instant departureTime,
            Instant arrivalTime,
            String originCode,
            String destinationCode,
            BigDecimal weightKg,
            String serviceLevel) {}

    record AmendmentCommand(
            String idempotencyKey,
            String providerReference,
            String flightNumber,
            Instant departureTime,
            Instant arrivalTime,
            BigDecimal weightKg,
            String serviceLevel) {}

    record CancellationCommand(String idempotencyKey, String providerReference, String reason) {}

    record BookingResult(
            String status,
            String providerReference,
            String confirmationNumber,
            BigDecimal confirmedWeightKg,
            String rawResponse) {}

    record FlightStatus(
            String flightStatus,
            Instant scheduledDeparture,
            Instant estimatedDeparture,
            Instant actualDeparture,
            Instant scheduledArrival,
            Instant estimatedArrival,
            Instant actualArrival,
            int departureDelayMinutes,
            int arrivalDelayMinutes,
            String providerEventId,
            String rawResponse) {}

    record AwbSubmissionResult(String status, String providerReference, String rawResponse) {}
}
