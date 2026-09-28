package com.logiplatform.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Normalized contract between AAL and an airline/cargo provider.
 *
 * <p>The booking domain depends only on this contract. Provider-specific
 * HTTP, JSON, XML, Cargo-XML and ONE Record details remain inside adapters.</p>
 */
public interface AirCargoProviderPort {

    String providerCode();

    ProviderCapabilities capabilities();

    List<FlightOffer> searchFlights(
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg);

    CapacitySnapshot capacity(String flightNumber, Instant date);

    BookingResult book(BookingCommand command);

    BookingResult getBooking(String providerReference);

    BookingResult amend(AmendmentCommand command);

    BookingResult cancel(CancellationCommand command);

    FlightStatus getFlightStatus(String flightNumber, String flightDate);

    /**
     * Preferred live status lookup when a provider returns an opaque
     * selection/reference that is safer than a flight-number lookup.
     * Existing adapters remain source-compatible through the default method.
     */
    default FlightStatus getFlightStatusByProviderReference(String providerReference) {
        throw new UnsupportedOperationException(
                "Provider does not expose status lookup by provider reference");
    }

    AwbSubmissionResult submitAwb(
            Map<String, Object> payload,
            String idempotencyKey);

    default AwbSubmissionResult getAwb(String providerReference) {
        throw new UnsupportedOperationException(
                "Provider does not expose AWB reconciliation");
    }

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
            List<String> standards) {
    }

    record CapacitySnapshot(
            String flightNumber,
            BigDecimal availableCapacityKg,
            BigDecimal totalCapacityKg,
            Instant asOf,
            String source) {
    }

    /**
     * Normalized live flight/rate offer.
     *
     * providerReference: complete provider selection token. For CargoAi this
     * is flightUUID|rateId.
     *
     * offerReference: provider flight/offer identifier. For CargoAi this is
     * flightUUID.
     *
     * rateReference/rateId: selected provider rate identifier.
     */
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
            String providerReference,
            String offerReference,
            String rateReference,
            String rateId,
            String rateName,
            String currency,
            BigDecimal totalPrice,
            BigDecimal unitPrice,
            String productCode,
            boolean bookable,
            String availableReason) {

        public FlightOffer(
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
                String providerReference) {
            this(
                    carrierCode,
                    carrierName,
                    flightNumber,
                    origin,
                    destination,
                    departure,
                    arrival,
                    totalCapacityKg,
                    availableCapacityKg,
                    serviceLevel,
                    status,
                    providerReference,
                    providerReference,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    null);
        }

        public FlightOffer(
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
                String providerReference,
                String offerReference,
                String rateReference,
                boolean bookable,
                String currency,
                BigDecimal totalPrice,
                BigDecimal unitPrice,
                String productCode,
                String availableReason) {
            this(
                    carrierCode,
                    carrierName,
                    flightNumber,
                    origin,
                    destination,
                    departure,
                    arrival,
                    totalCapacityKg,
                    availableCapacityKg,
                    serviceLevel,
                    status,
                    providerReference,
                    offerReference,
                    rateReference,
                    rateReference,
                    null,
                    currency,
                    totalPrice,
                    unitPrice,
                    productCode,
                    bookable,
                    availableReason);
        }
    }

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
            String serviceLevel,
            String providerReference,
            String offerReference,
            String rateReference) {

        public BookingCommand(
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
                String serviceLevel) {
            this(
                    idempotencyKey,
                    shipmentId,
                    carrierCode,
                    carrierName,
                    flightNumber,
                    departureTime,
                    arrivalTime,
                    originCode,
                    destinationCode,
                    weightKg,
                    serviceLevel,
                    null,
                    null,
                    null);
        }

        public BookingCommand(
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
                String serviceLevel,
                String providerReference) {
            this(
                    idempotencyKey,
                    shipmentId,
                    carrierCode,
                    carrierName,
                    flightNumber,
                    departureTime,
                    arrivalTime,
                    originCode,
                    destinationCode,
                    weightKg,
                    serviceLevel,
                    providerReference,
                    null,
                    null);
        }

        public BookingCommand(
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
                String serviceLevel,
                String providerReference,
                String rateReference) {
            this(
                    idempotencyKey,
                    shipmentId,
                    carrierCode,
                    carrierName,
                    flightNumber,
                    departureTime,
                    arrivalTime,
                    originCode,
                    destinationCode,
                    weightKg,
                    serviceLevel,
                    providerReference,
                    null,
                    rateReference);
        }
    }

    record AmendmentCommand(
            String idempotencyKey,
            String providerReference,
            String flightNumber,
            Instant departureTime,
            Instant arrivalTime,
            BigDecimal weightKg,
            String serviceLevel) {
    }

    record CancellationCommand(
            String idempotencyKey,
            String providerReference,
            String reason) {
    }

    record BookingResult(
            String status,
            String providerReference,
            String confirmationNumber,
            BigDecimal confirmedWeightKg,
            String rawResponse) {
    }

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
            String rawResponse) {
    }

    record AwbSubmissionResult(
            String status,
            String providerReference,
            String rawResponse) {
    }
}
