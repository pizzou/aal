package com.logiplatform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.integration.control.CircuitBreakerService;
import com.logiplatform.integration.control.ExternalOperationException;
import com.logiplatform.integration.control.IntegrationMetricsService;
import com.logiplatform.integration.control.ProviderRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Direct Lufthansa Cargo shipment-tracking adapter.
 *
 * <p>This adapter intentionally implements tracking only. Lufthansa Cargo's
 * private smartBooking API requires partner registration and tenant-specific
 * endpoint/key issuance; AAL does not invent that private booking contract.</p>
 *
 * <p>Provider reference format accepted by this adapter:
 * <code>AWB:020-12345678</code>, <code>020-12345678</code>, or the equivalent
 * prefix/number separated by <code>|</code>.</p>
 */
@Component
public class LufthansaCargoTrackingProvider implements AirCargoProviderPort {
    private static final String CODE = "LHCARGO";
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final CircuitBreakerService circuitBreaker;
    private final ProviderRateLimiter rateLimiter;
    private final IntegrationMetricsService metrics;
    private final boolean enabled;
    private final String baseUrl;
    private final String trackingPath;
    private final String apiKey;

    public LufthansaCargoTrackingProvider(
            RestTemplate rest,
            ObjectMapper mapper,
            @Value("${aircargo.lufthansa.enabled:false}") boolean enabled,
            @Value("${aircargo.lufthansa.base-url:https://api.lufthansa-cargo.com}") String baseUrl,
            @Value("${aircargo.lufthansa.tracking-path:/lhcargo/handling/shipmenttracking/v4/shipment}") String trackingPath,
            @Value("${aircargo.lufthansa.api-key:}") String apiKey,
            CircuitBreakerService circuitBreaker,
            ProviderRateLimiter rateLimiter,
            IntegrationMetricsService metrics) {
        this.rest = rest;
        this.mapper = mapper;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.enabled = enabled;
        this.baseUrl = trim(baseUrl);
        this.trackingPath = normalizePath(trackingPath, "/lhcargo/handling/shipmenttracking/v4/shipment");
        this.apiKey = trim(apiKey);
    }

    @Override
    public String providerCode() {
        return CODE;
    }

    @Override
    public boolean configured() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public List<String> configurationIssues() {
        List<String> issues = new ArrayList<>();
        if (!enabled) issues.add("AIRCARGO_LH_CARGO_ENABLED is false");
        if (baseUrl.isBlank()) issues.add("AIRCARGO_LH_CARGO_BASE_URL is missing");
        if (apiKey.isBlank()) issues.add("AIRCARGO_LH_CARGO_API_KEY is missing");
        return issues;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                false, false, false, false, false,
                configured(), false, false, false, !apiKey.isBlank(),
                List.of("Lufthansa-Cargo-Tracking-API"));
    }

    @Override
    public List<FlightOffer> searchFlights(String origin, String destination, Instant from, Instant to, java.math.BigDecimal weightKg) {
        throw new UnsupportedOperationException("Lufthansa Cargo direct schedule/search API is not configured");
    }

    @Override
    public CapacitySnapshot capacity(String flightNumber, Instant date) {
        throw new UnsupportedOperationException("Lufthansa Cargo direct capacity API is not configured");
    }

    @Override
    public BookingResult book(BookingCommand command) {
        throw new UnsupportedOperationException("Lufthansa Cargo smartBooking API requires tenant partner provisioning");
    }

    @Override
    public BookingResult getBooking(String providerReference) {
        throw new UnsupportedOperationException("Lufthansa Cargo smartBooking booking retrieval is not configured");
    }

    @Override
    public BookingResult amend(AmendmentCommand command) {
        throw new UnsupportedOperationException("Lufthansa Cargo smartBooking amendment is not configured");
    }

    @Override
    public BookingResult cancel(CancellationCommand command) {
        throw new UnsupportedOperationException("Lufthansa Cargo smartBooking cancellation is not configured");
    }

    @Override
    public FlightStatus getFlightStatus(String flightNumber, String flightDate) {
        throw new UnsupportedOperationException("Use AWB provider reference for Lufthansa Cargo tracking");
    }

    @Override
    public FlightStatus getFlightStatusByProviderReference(String providerReference) {
        requireConfigured();
        AwbParts awb = parseAwb(providerReference);
        long started = System.nanoTime();
        try {
            rateLimiter.acquire(CODE);
            if (!circuitBreaker.allow(CODE)) {
                throw new ExternalOperationException("PROVIDER_CIRCUIT_OPEN", null, false, 503);
            }

            String url = baseUrl.replaceAll("/$", "")
                    + trackingPath
                    + "?aWBPrefix=" + awb.prefix
                    + "&aWBNumber=" + awb.number;
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (!apiKey.isBlank()) headers.set("X-API-Key", apiKey);

            ResponseEntity<String> response = rest.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            FlightStatus status = mapStatus(root, providerReference);

            long latency = (System.nanoTime() - started) / 1_000_000L;
            circuitBreaker.success(CODE, latency, response.getStatusCode().value());
            metrics.providerSuccess(CODE, latency);
            return status;
        } catch (HttpStatusCodeException ex) {
            long latency = (System.nanoTime() - started) / 1_000_000L;
            circuitBreaker.failure(CODE, latency, ex.getStatusCode().value());
            metrics.providerFailure(CODE, latency, "HTTP_" + ex.getStatusCode().value());
            throw new ExternalOperationException(
                    "LHCARGO_TRACK_HTTP_" + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(),
                    ex,
                    false,
                    ex.getStatusCode().value());
        } catch (ExternalOperationException ex) {
            metrics.providerFailure(CODE, (System.nanoTime() - started) / 1_000_000L, "LHCARGO_PROVIDER_ERROR");
            throw ex;
        } catch (Exception ex) {
            long latency = (System.nanoTime() - started) / 1_000_000L;
            circuitBreaker.failure(CODE, latency, null);
            metrics.providerFailure(CODE, latency, ex.getClass().getSimpleName());
            throw new ExternalOperationException("LHCARGO_TRACK_FAILED", ex, false, null);
        }
    }

    @Override
    public AwbSubmissionResult submitAwb(Map<String, Object> payload, String idempotencyKey) {
        throw new UnsupportedOperationException("Lufthansa Cargo AWB messaging is a separate capability");
    }

    private FlightStatus mapStatus(JsonNode root, String providerReference) {
        JsonNode trackingStatus = firstObject(root, "shipmentTrackingStatus", "shipmentTracking", "data");
        JsonNode shipment = firstObject(trackingStatus, "shipment", "shipmentTracking", "data");
        if (shipment == null) shipment = trackingStatus == null ? root : trackingStatus;

        JsonNode movement = firstObject(
                trackingStatus,
                "flightMovementDetails",
                "flightMovement",
                "movement");

        String status = firstText(shipment, "statusCode", "status", "eventCode", "handlingStatus");
        status = normalizeStatus(status);

        Instant scheduledDeparture = firstInstant(
                movement,
                "scheduledDeparture",
                "flightScheduledDeparture",
                "departureTime",
                "flightDate");
        Instant estimatedDeparture = firstInstant(movement, "estimatedDeparture", "estimatedDepartureTime");
        Instant actualDeparture = firstInstant(
                shipment,
                "actualDeparture",
                "actualDepartureTime",
                "departureTimeActual");
        Instant scheduledArrival = firstInstant(
                movement,
                "scheduledArrival",
                "flightScheduledArrival",
                "arrivalTime");
        Instant estimatedArrival = firstInstant(movement, "estimatedArrival", "estimatedArrivalTime", "etaUTC", "eta");
        Instant actualArrival = firstInstant(
                shipment,
                "actualArrival",
                "actualArrivalTime",
                "arrivalTimeActual");

        if (actualDeparture == null || actualArrival == null) {
            JsonNode latestEvent = latestEvent(trackingStatus);
            if (actualDeparture == null && isEvent(latestEvent, "DEP")) {
                actualDeparture = firstInstant(latestEvent, "actualTime", "timestamp", "eventTime");
            }
            if (actualArrival == null && isEvent(latestEvent, "ARR")) {
                actualArrival = firstInstant(latestEvent, "actualTime", "timestamp", "eventTime");
            }
        }

        int depDelay = delayMinutes(scheduledDeparture, estimatedDeparture, actualDeparture);
        int arrDelay = delayMinutes(scheduledArrival, estimatedArrival, actualArrival);
        String eventId = firstText(trackingStatus, "eventId", "trackingEventId", "lastEventId");

        return new FlightStatus(
                status,
                scheduledDeparture,
                estimatedDeparture,
                actualDeparture,
                scheduledArrival,
                estimatedArrival,
                actualArrival,
                depDelay,
                arrDelay,
                eventId.isBlank() ? providerReference : eventId,
                root.toString());
    }

    private static JsonNode firstObject(JsonNode node, String... names) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                if (value.isArray()) return value.size() == 0 ? null : value.get(0);
                return value;
            }
        }
        return null;
    }

    private static JsonNode latestEvent(JsonNode trackingStatus) {
        if (trackingStatus == null || trackingStatus.isNull() || trackingStatus.isMissingNode()) return null;
        JsonNode events = trackingStatus.path("events").path("event");
        if (events.isArray() && events.size() > 0) return events.get(0);
        JsonNode statusEvents = trackingStatus.path("shipmentStatusEvents").path("event");
        if (statusEvents.isArray() && statusEvents.size() > 0) return statusEvents.get(0);
        return null;
    }

    private static boolean isEvent(JsonNode event, String code) {
        return event != null
                && code.equalsIgnoreCase(firstText(event, "type", "eventType", "code", "status"));
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return "TRACKING";
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "BKD" -> "BOOKED";
            case "RCS" -> "RECEIVED_FROM_SHIPPER";
            case "MAN" -> "MANIFESTED";
            case "DEP" -> "DEPARTED";
            case "ARR" -> "ARRIVED";
            case "RCF" -> "RECEIVED_AT_DESTINATION";
            case "NFD" -> "NOTIFIED_FOR_DELIVERY";
            case "DLV" -> "DELIVERED";
            case "DDL" -> "DELIVERY_DELIVERED";
            case "RCT" -> "RECEIPT_CONFIRMED";
            case "TFD" -> "TRANSFERRED";
            case "DIS" -> "DISCREPANCY";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static int delayMinutes(Instant scheduled, Instant estimated, Instant actual) {
        Instant observed = actual != null ? actual : estimated;
        if (scheduled == null || observed == null) return 0;
        long minutes = java.time.Duration.between(scheduled, observed).toMinutes();
        return (int) Math.max(-1440, Math.min(10080, minutes));
    }

    private static JsonNode firstNode(JsonNode root, String... names) {
        if (root == null) return root;
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull()) {
                if (node.isArray() && node.size() > 0) return node.get(0);
                return node;
            }
        }
        return root;
    }

    private static String firstText(JsonNode node, String... names) {
        if (node == null) return "";
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return "";
    }

    private static Instant firstInstant(JsonNode node, String... names) {
        String value = firstText(node, names);
        if (value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private AwbParts parseAwb(String reference) {
        String value = trim(reference).toUpperCase(Locale.ROOT);
        if (value.startsWith("AWB:")) value = value.substring(4).trim();
        value = value.replace("|", "-").replace(" ", "");
        String[] pieces = value.split("-");
        String prefix;
        String number;
        if (pieces.length >= 2) {
            prefix = pieces[0];
            number = pieces[1];
        } else if (value.matches("\\d{11}")) {
            prefix = value.substring(0, 3);
            number = value.substring(3);
        } else {
            throw new IllegalArgumentException("Lufthansa Cargo tracking reference must be an AWB such as 020-12345678");
        }
        if (!prefix.matches("\\d{3}") || !number.matches("\\d{8}")) {
            throw new IllegalArgumentException("Invalid Lufthansa Cargo AWB format: " + reference);
        }
        return new AwbParts(prefix, number);
    }

    private void requireConfigured() {
        if (!configured()) throw new IllegalStateException(String.join("; ", configurationIssues()));
    }

    private static String normalizePath(String value, String fallback) {
        String p = trim(value);
        if (p.isBlank()) p = fallback;
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record AwbParts(String prefix, String number) {}
}
