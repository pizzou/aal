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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CargoAi CargoCONNECT adapter for live air cargo search, booking,
 * cancellation and flight/AWB tracking.
 *
 * <p>The adapter keeps the normalized AAL contract independent of CargoAi.
 * The exact selected offer is represented as:</p>
 *
 * <pre>providerReference = flightUUID|rateId</pre>
 *
 * <p>After booking, the providerReference stored on the AAL booking is the
 * CargoAi flight UUID. This is intentional: it is the stable key used by the
 * Track & Trace endpoint and by cancellation.</p>
 */
@Component
public class CargoAiAirCargoProvider implements AirCargoProviderPort {

    private static final String DEFAULT_BASE_URL = "https://api.cargoai.co/solutions";
    private static final String PROVIDER_CODE = "CARGOAI";

    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final CircuitBreakerService circuitBreaker;
    private final ProviderRateLimiter rateLimiter;
    private final IntegrationMetricsService metrics;

    private final String baseUrl;
    private final String searchPath;
    private final String bookingPath;
    private final String trackPath;
    private final String cancellationPath;
    private final String apiKey;
    private final String email;
    private final String iata;
    private final String cass;
    private final String country;
    private final String companyName;
    private final String firstName;
    private final String lastName;
    private final int searchPollAttempts;
    private final long searchPollDelayMs;

    public CargoAiAirCargoProvider(
            RestTemplate rest,
            ObjectMapper mapper,
            @Value("${aircargo.cargoai.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${aircargo.cargoai.search-path:/search}") String searchPath,
            @Value("${aircargo.cargoai.booking-path:/book}") String bookingPath,
            @Value("${aircargo.cargoai.track-path:/track}") String trackPath,
            @Value("${aircargo.cargoai.cancel-path:/bookings}") String cancellationPath,
            @Value("${aircargo.cargoai.api-key:${AIRCARGO_CARGOAI_API_KEY:}}") String apiKey,
            @Value("${aircargo.cargoai.user.email:${AIRCARGO_CARGOAI_USER_EMAIL:}}") String email,
            @Value("${aircargo.cargoai.user.iata:${AIRCARGO_CARGOAI_USER_IATA:}}") String iata,
            @Value("${aircargo.cargoai.user.cass:${AIRCARGO_CARGOAI_USER_CASS:}}") String cass,
            @Value("${aircargo.cargoai.user.country:${AIRCARGO_CARGOAI_USER_COUNTRY:RW}}") String country,
            @Value("${aircargo.cargoai.user.company-name:${AIRCARGO_CARGOAI_COMPANY_NAME:Aviation Africa Logistics Ltd}}") String companyName,
            @Value("${aircargo.cargoai.user.first-name:${AIRCARGO_CARGOAI_USER_FIRST_NAME:Operations}}") String firstName,
            @Value("${aircargo.cargoai.user.last-name:${AIRCARGO_CARGOAI_USER_LAST_NAME:AAL}}") String lastName,
            @Value("${aircargo.cargoai.search-poll-attempts:${AIRCARGO_CARGOAI_SEARCH_POLL_ATTEMPTS:4}}") int searchPollAttempts,
            @Value("${aircargo.cargoai.search-poll-delay-ms:${AIRCARGO_CARGOAI_SEARCH_POLL_DELAY_MS:750}}") long searchPollDelayMs,
            CircuitBreakerService circuitBreaker,
            ProviderRateLimiter rateLimiter,
            IntegrationMetricsService metrics) {

        this.rest = rest;
        this.mapper = mapper;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;

        this.baseUrl = strip(baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        this.searchPath = normalizePath(searchPath, "/search");
        this.bookingPath = normalizePath(bookingPath, "/book");
        this.trackPath = normalizePath(trackPath, "/track");
        this.cancellationPath = normalizePath(cancellationPath, "/bookings");

        this.apiKey = trim(apiKey);
        this.email = trim(email);
        this.iata = trim(iata);
        this.cass = trim(cass);
        this.country = blank(country) ? "RW" : country.trim().toUpperCase(Locale.ROOT);
        this.companyName = blank(companyName) ? "Aviation Africa Logistics Ltd" : companyName.trim();
        this.firstName = blank(firstName) ? "Operations" : firstName.trim();
        this.lastName = blank(lastName) ? "AAL" : lastName.trim();
        this.searchPollAttempts = Math.max(1, Math.min(8, searchPollAttempts));
        this.searchPollDelayMs = Math.max(150L, Math.min(5000L, searchPollDelayMs));
    }

    public boolean configured() {
        return !apiKey.isBlank()
                && (!email.isBlank() || (!iata.isBlank() && !cass.isBlank()));
    }

    /**
     * Human-readable prerequisites for the integration diagnostics endpoint.
     */
    public List<String> configurationIssues() {
        List<String> issues = new ArrayList<>();
        if (apiKey.isBlank()) {
            issues.add("AIRCARGO_CARGOAI_API_KEY is missing");
        }
        if (email.isBlank() && (iata.isBlank() || cass.isBlank())) {
            issues.add("CargoAi user email or IATA+CASS credentials are missing");
        }
        return issues;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public ProviderCapabilities capabilities() {
        boolean on = configured();
        return new ProviderCapabilities(
                on,  // schedules/rates
                on,  // live capacity is exposed through live search/rates
                on,  // eBooking
                false,
                on,  // cancellation
                on,  // Track & Trace
                false,
                on,  // CargoAi callback integration is supported by AAL
                false,
                on,
                List.of("CARGOCONNECT", "IATA", "CARGO-XML"));
    }

    @Override
    public List<FlightOffer> searchFlights(
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {

        requireConfigured();
        validateSearch(origin, destination, from, to, weightKg);

        String normalizedOrigin = origin.trim().toUpperCase(Locale.ROOT);
        String normalizedDestination = destination.trim().toUpperCase(Locale.ROOT);
        BigDecimal weight = weightKg.stripTrailingZeros();

        Map<String, Object> shipment = shipmentPayload(weight);
        Map<String, Object> user = userPayload();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", normalizedOrigin);
        body.put("destination", normalizedDestination);
        body.put("departureDate", LocalDate.ofInstant(from, ZoneOffset.UTC).toString());
        body.put("offset", Math.min(5, Math.max(0, (int) Math.ceil(Duration.between(from, to).toHours() / 24d))));
        body.put("timeout", 25);
        body.put("shipment", shipment);
        body.put("user", user);
        body.put("filters", Map.of("withRateOnly", true, "liveRequests", true));

        JsonNode root = post(searchPath, body, "SEARCH");
        root = resolveAsyncSearch(root, body);
        JsonNode flights = root.path("flights");

        if (!flights.isArray()) {
            return List.of();
        }

        List<FlightOffer> result = new ArrayList<>();

        for (JsonNode flight : flights) {
            FlightOffer offer = toFlightOffer(
                    flight,
                    normalizedOrigin,
                    normalizedDestination,
                    weight);
            if (offer != null) {
                result.add(offer);
            }
        }

        return result;
    }

    private FlightOffer toFlightOffer(
            JsonNode flight,
            String normalizedOrigin,
            String normalizedDestination,
            BigDecimal requestedWeight) {

        String flightUuid = firstText(flight, "flightUUID", "flightUID", "id");
        String carrier = firstText(flight, "airlineCode", "carrierCode");
        String carrierName = firstText(flight, "airlineName", "carrierName", "airline");
        String number = firstText(flight, "flightNumber", "flightNo");
        Instant departure = firstInstant(flight, "departureTime", "departure", "scheduledDeparture");
        Instant arrival = firstInstant(flight, "arrivalTime", "arrival", "scheduledArrival");

        if (blank(flightUuid) || blank(number) || departure == null) {
            return null;
        }

        boolean available = flight.path("available").asBoolean(false);
        JsonNode features = flight.path("features");
        boolean providerBookable = features.path("bookable").asBoolean(false);
        String providerReason = firstText(flight, "availableReason", "reason");

        JsonNode rates = flight.path("rates");
        if (!rates.isArray() || rates.isEmpty()) {
            if (!available || !providerBookable) {
                return null;
            }

            // A booking without a rate is not safe for CargoAi eBooking.
            // Keep it visible as live availability, but not bookable.
            return new FlightOffer(
                    carrier,
                    blank(carrierName) ? carrier : carrierName,
                    number,
                    normalizedOrigin,
                    normalizedDestination,
                    departure,
                    arrival,
                    requestedWeight,
                    requestedWeight,
                    "STANDARD",
                    "LIVE",
                    flightUuid,
                    flightUuid,
                    null,
                    null,
                    "STANDARD",
                    null,
                    null,
                    null,
                    null,
                    false,
                    "Carrier returned a flight without a rate; refresh availability before booking");
        }

        JsonNode best = chooseBestRate(rates);
        if (best == null) {
            return null;
        }

        String rateId = firstText(best, "id", "rateId");
        if (blank(rateId)) {
            return null;
        }

        String rateName = firstText(best, "name", "type", "product");
        String currency = firstText(best, "currency", "currencyCode");
        String productCode = firstText(best, "productCode", "product");
        BigDecimal totalPrice = decimal(best, "total");
        BigDecimal chargeableWeight = firstDecimal(best, "chargeableWeight", "chargeable_weight");
        if (chargeableWeight == null || chargeableWeight.signum() <= 0) {
            chargeableWeight = requestedWeight;
        }

        BigDecimal totalCapacity = firstDecimal(
                flight,
                "totalCapacityKg",
                "totalCapacity",
                "capacityKg");
        BigDecimal availableCapacity = firstDecimal(
                flight,
                "availableCapacityKg",
                "availableCapacity",
                "capacityAvailableKg");
        if (totalCapacity == null || totalCapacity.signum() <= 0) {
            totalCapacity = chargeableWeight;
        }
        if (availableCapacity == null || availableCapacity.signum() < 0) {
            availableCapacity = bookableWeightAvailable(available, chargeableWeight);
        }
        if (availableCapacity.compareTo(totalCapacity) > 0) {
            availableCapacity = totalCapacity;
        }

        boolean bookable = available && providerBookable;
        String providerReference = flightUuid + "|" + rateId;
        BigDecimal unitPrice = requestedWeight.signum() > 0 && totalPrice.signum() >= 0
                ? totalPrice.divide(requestedWeight, 4, RoundingMode.HALF_UP)
                : null;

        if (!bookable && blank(providerReason)) {
            providerReason = "Provider returned the offer as not bookable";
        }

        return new FlightOffer(
                carrier,
                blank(carrierName) ? carrier : carrierName,
                number,
                normalizedOrigin,
                normalizedDestination,
                departure,
                arrival,
                totalCapacity,
                bookable ? availableCapacity : BigDecimal.ZERO,
                blank(rateName) ? "LIVE" : rateName,
                "LIVE",
                providerReference,
                flightUuid,
                rateId,
                rateId,
                rateName,
                currency,
                totalPrice,
                unitPrice,
                productCode,
                bookable,
                providerReason);
    }

    @Override
    public CapacitySnapshot capacity(String flightNumber, Instant date) {
        requireConfigured();
        throw new UnsupportedOperationException(
                "CargoAi exposes live capacity through Quote & Book search; use flight search for availability");
    }

    @Override
    public BookingResult book(BookingCommand command) {
        requireConfigured();
        validateBooking(command);

        String flightUuid = firstNonBlank(command.offerReference(), extractOfferReference(command.providerReference()));
        String rateId = firstNonBlank(command.rateReference(), extractRateReference(command.providerReference()));

        if (blank(flightUuid) || blank(rateId)) {
            throw new IllegalArgumentException(
                    "A live CargoAi flight and rate selection is required before booking");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightUUID", flightUuid);
        body.put("rateId", rateId);
        body.put("user", userPayload());
        body.put("shipment", shipmentPayload(command.weightKg()));

        Map<String, Object> itinerary = new LinkedHashMap<>();
        itinerary.put("origin", normalizeAirport(command.originCode()));
        itinerary.put("destination", normalizeAirport(command.destinationCode()));
        itinerary.put("airlineCode", command.carrierCode().trim().toUpperCase(Locale.ROOT));
        itinerary.put("departureDates", List.of(command.departureTime().toString().substring(0, 10)));
        itinerary.put("flightNumbers", List.of(command.flightNumber()));
        body.put("itinerary", itinerary);
        body.put("comment", "AAL shipment " + safe(command.shipmentId()));

        JsonNode root = request(
                HttpMethod.POST,
                bookingPath,
                body,
                command.idempotencyKey(),
                "BOOK");

        String status = firstText(root, "bookingStatus", "status");
        status = blank(status) ? "PENDING" : status.toUpperCase(Locale.ROOT);
        if ("BOOKED".equals(status) || "BOOKING_CONFIRMED".equals(status)) {
            status = "CONFIRMED";
        }

        String error = firstText(root, "error", "errorCode");
        String description = firstText(root, "description", "message", "rejectedReason");
        if ("FAILED".equals(status) || "REJECTED".equals(status) || !blank(error)) {
            String providerMessage = blank(description) ? "CargoAi rejected the booking" : description;
            throw new ExternalOperationException(
                    "CargoAi booking rejected: " + providerMessage,
                    null,
                    false,
                    409);
        }

        String awb = firstText(root, "awb", "awbNumber", "airWaybill");
        String returnedFlightUuid = firstText(root, "flightUUID", "flightUid", "flightUID");
        if (blank(returnedFlightUuid)) {
            returnedFlightUuid = flightUuid;
        }
        String confirmation = firstText(root, "confirmationNumber", "confirmation", "bookingReference", "id");
        if (blank(confirmation) && !blank(awb)) {
            confirmation = awb;
        }

        return new BookingResult(
                status,
                returnedFlightUuid,
                blank(confirmation) ? awb : confirmation,
                decimalOrDefault(root, "confirmedWeightKg", command.weightKg()),
                root.toString());
    }

    @Override
    public BookingResult getBooking(String providerReference) {
        requireConfigured();
        String flightUuid = extractFlightUuid(providerReference);
        if (blank(flightUuid)) {
            throw new IllegalArgumentException("A CargoAi flight UUID is required for booking lookup");
        }

        JsonNode root = track(flightUuid);
        JsonNode normalizedRoot = firstArrayElement(root);
        JsonNode booking = firstObject(normalizedRoot, "bookingResponse", "booking", "bookingResult");
        JsonNode source = booking == null ? normalizedRoot : booking;

        String status = firstText(source, "bookingStatus", "status");
        status = blank(status) ? inferBookingStatus(root) : status.toUpperCase(Locale.ROOT);
        String awb = firstText(source, "awb", "awbNumber", "airWaybill");
        String confirmation = firstText(source, "confirmationNumber", "confirmation", "bookingReference", "id");
        if (blank(confirmation)) {
            confirmation = awb;
        }

        return new BookingResult(
                status,
                flightUuid,
                confirmation,
                firstDecimal(source, "confirmedWeightKg", "weightKg"),
                root.toString());
    }

    @Override
    public BookingResult amend(AmendmentCommand command) {
        throw new UnsupportedOperationException(
                "CargoAi Quote & Book amendment is handled by a cancel-and-rebook workflow");
    }

    @Override
    public BookingResult cancel(CancellationCommand command) {
        requireConfigured();
        String flightUuid = extractFlightUuid(command.providerReference());
        if (blank(flightUuid)) {
            throw new IllegalArgumentException("A CargoAi flight UUID is required for cancellation");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightUUID", flightUuid);
        body.put("action", "CANCEL");
        if (!blank(command.reason())) {
            body.put("cancelledReasons", List.of(command.reason()));
        }

        JsonNode root = request(
                HttpMethod.PUT,
                cancellationPath + "/" + encodePath(flightUuid),
                body,
                command.idempotencyKey(),
                "CANCEL");

        String status = firstText(root, "bookingStatus", "status");
        if (blank(status)) {
            status = "CANCELLED";
        }

        String confirmation = firstText(root, "confirmationNumber", "confirmation", "bookingReference", "awb");
        return new BookingResult(
                status.toUpperCase(Locale.ROOT),
                flightUuid,
                confirmation,
                null,
                root.toString());
    }

    @Override
    public FlightStatus getFlightStatus(String flightNumber, String flightDate) {
        throw new UnsupportedOperationException(
                "CargoAi Track & Trace requires the live provider flight UUID; use the booked flight provider reference");
    }

    @Override
    public FlightStatus getFlightStatusByProviderReference(String providerReference) {
        requireConfigured();
        String flightUuid = extractFlightUuid(providerReference);
        if (blank(flightUuid)) {
            throw new IllegalArgumentException("A CargoAi flight UUID is required for Track & Trace");
        }

        JsonNode root = track(flightUuid);
        return toFlightStatus(root, flightUuid);
    }

    @Override
    public AwbSubmissionResult submitAwb(
            Map<String, Object> payload,
            String idempotencyKey) {
        throw new UnsupportedOperationException(
                "CargoAi FWB/FHL eAWB messaging is a separate provider capability");
    }

    private JsonNode track(String flightUuid) {
        return request(
                HttpMethod.GET,
                trackPath + "?flight-uuid=" + encodeQuery(flightUuid),
                null,
                "track-" + flightUuid,
                "TRACK");
    }

    private FlightStatus toFlightStatus(JsonNode root, String providerEventIdFallback) {
        JsonNode node = firstArrayElement(root);
        JsonNode flight = firstObject(node, "flight", "flightDetails");
        JsonNode source = flight == null ? node : flight;

        Instant scheduledDeparture = firstInstant(
                source,
                "scheduledDeparture",
                "departureScheduled",
                "departureTime",
                "scheduledDepartureTime");
        Instant estimatedDeparture = firstInstant(
                source,
                "estimatedDeparture",
                "departureEstimated",
                "estimatedDepartureTime");
        Instant actualDeparture = firstInstant(
                source,
                "actualDeparture",
                "departureActual",
                "actualDepartureTime");
        Instant scheduledArrival = firstInstant(
                source,
                "scheduledArrival",
                "arrivalScheduled",
                "arrivalTime",
                "scheduledArrivalTime");
        Instant estimatedArrival = firstInstant(
                source,
                "estimatedArrival",
                "arrivalEstimated",
                "estimatedArrivalTime");
        Instant actualArrival = firstInstant(
                source,
                "actualArrival",
                "arrivalActual",
                "actualArrivalTime");

        String status = firstText(source, "flightStatus", "status", "state");
        String eventId = firstText(source, "providerEventId", "eventId", "trackingEventId");

        EventSummary events = summarizeEvents(root);
        if (!blank(events.status())) {
            status = events.status();
        }
        if (events.actualDeparture() != null) {
            actualDeparture = events.actualDeparture();
        }
        if (events.actualArrival() != null) {
            actualArrival = events.actualArrival();
        }
        if (blank(eventId)) {
            eventId = events.eventId();
        }

        int departureDelay = delayMinutes(scheduledDeparture, estimatedDeparture, actualDeparture);
        int arrivalDelay = delayMinutes(scheduledArrival, estimatedArrival, actualArrival);

        if (blank(status)) {
            status = "SCHEDULED";
        }

        return new FlightStatus(
                status.toUpperCase(Locale.ROOT),
                scheduledDeparture,
                estimatedDeparture,
                actualDeparture,
                scheduledArrival,
                estimatedArrival,
                actualArrival,
                departureDelay,
                arrivalDelay,
                blank(eventId) ? providerEventIdFallback : eventId,
                root.toString());
    }

    private EventSummary summarizeEvents(JsonNode root) {
        List<JsonNode> arrays = new ArrayList<>();
        addArrayIfPresent(arrays, root, "trackingEvents");
        addArrayIfPresent(arrays, root, "tracking");
        addArrayIfPresent(arrays, root, "events");

        JsonNode arrayRoot = firstArrayElement(root);
        addArrayIfPresent(arrays, arrayRoot, "trackingEvents");
        addArrayIfPresent(arrays, arrayRoot, "events");
        JsonNode flight = firstObject(arrayRoot, "flight", "flightDetails");
        addArrayIfPresent(arrays, flight, "trackingEvents");
        addArrayIfPresent(arrays, flight, "events");

        Instant actualDeparture = null;
        Instant actualArrival = null;
        String status = "";
        String eventId = "";

        for (JsonNode array : arrays) {
            for (JsonNode event : array) {
                String code = firstText(event, "eventCode", "code", "eventType", "status").toUpperCase(Locale.ROOT);
                Instant occurred = firstInstant(event, "occurredAt", "eventDate", "timestamp", "eventTime", "date");
                if (blank(eventId)) {
                    eventId = firstText(event, "providerEventId", "eventId", "id");
                }

                switch (code) {
                    case "DEP", "DEPARTED", "FLIGHT_DEPARTED" -> {
                        actualDeparture = firstNonNull(actualDeparture, occurred);
                        status = "DEPARTED";
                    }
                    case "ARR", "ARRIVED", "FLIGHT_ARRIVED" -> {
                        actualArrival = firstNonNull(actualArrival, occurred);
                        status = "ARRIVED";
                    }
                    case "RCF", "RECEIVED_FROM_FLIGHT" -> {
                        status = "RECEIVED_AT_DESTINATION";
                    }
                    case "DLV", "DELIVERED" -> status = "DELIVERED";
                    case "CNL", "CANCELLED", "CANCELED" -> status = "CANCELLED";
                    default -> {
                    }
                }
            }
        }

        return new EventSummary(status, eventId, actualDeparture, actualArrival);
    }

    private JsonNode resolveAsyncSearch(JsonNode root, Map<String, Object> originalBody) {
        String operationId = firstText(root, "id", "requestId", "searchId");
        if (blank(operationId) || root.path("flights").isArray()) {
            return root;
        }

        Map<String, Object> pollBody = new LinkedHashMap<>(originalBody);
        pollBody.put("id", operationId);

        JsonNode current = root;
        for (int i = 0; i < searchPollAttempts && !current.path("flights").isArray(); i++) {
            sleep(searchPollDelayMs);
            current = post(searchPath, pollBody, "SEARCH_POLL");
        }
        return current;
    }

    private JsonNode post(String path, Object body, String operation) {
        return request(HttpMethod.POST, path, body, null, operation);
    }

    private JsonNode request(
            HttpMethod method,
            String path,
            Object body,
            String idempotencyKey,
            String operation) {

        if (!circuitBreaker.allow(providerCode())) {
            throw new ExternalOperationException(
                    "CargoAi circuit breaker is OPEN",
                    null,
                    true,
                    null);
        }

        rateLimiter.acquire(providerCode());
        long started = System.nanoTime();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("x-api-key", apiKey);
            if (!blank(idempotencyKey)) {
                headers.set("Idempotency-Key", idempotencyKey);
            }

            String url = path.startsWith("http") ? path : baseUrl + normalizePath(path, "/");
            ResponseEntity<String> response = rest.exchange(
                    url,
                    method,
                    new HttpEntity<>(body, headers),
                    String.class);

            long latency = elapsedMillis(started);
            int status = response.getStatusCode().value();

            if (!response.getStatusCode().is2xxSuccessful()) {
                boolean retryable = response.getStatusCode().is5xxServerError()
                        || status == 408
                        || status == 429;
                circuitBreaker.failure(providerCode(), latency, status);
                metrics.providerFailure(providerCode(), latency, Integer.toString(status));
                throw new ExternalOperationException(
                        "CargoAi " + operation + " returned HTTP " + status,
                        null,
                        retryable,
                        status);
            }

            circuitBreaker.success(providerCode(), latency, status);
            metrics.providerSuccess(providerCode(), latency);

            String raw = response.getBody();
            return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);

        } catch (HttpStatusCodeException ex) {
            long latency = elapsedMillis(started);
            int status = ex.getStatusCode().value();
            circuitBreaker.failure(providerCode(), latency, status);
            metrics.providerFailure(providerCode(), latency, Integer.toString(status));

            boolean retryable = ex.getStatusCode().is5xxServerError()
                    || status == 408
                    || status == 429;

            throw new ExternalOperationException(
                    "CargoAi " + operation + " API error: "
                            + safeErrorBody(ex.getResponseBodyAsString()),
                    ex,
                    retryable,
                    status);

        } catch (ExternalOperationException ex) {
            long latency = elapsedMillis(started);
            circuitBreaker.failure(providerCode(), latency, ex.httpStatus());
            metrics.providerFailure(
                    providerCode(),
                    latency,
                    ex.httpStatus() == null
                            ? ex.getClass().getSimpleName()
                            : Integer.toString(ex.httpStatus()));
            throw ex;

        } catch (Exception ex) {
            long latency = elapsedMillis(started);
            circuitBreaker.failure(providerCode(), latency, null);
            metrics.providerFailure(providerCode(), latency, ex.getClass().getSimpleName());
            throw new ExternalOperationException(
                    "CargoAi " + operation + " connection failed: " + ex.getMessage(),
                    ex,
                    method != HttpMethod.GET,
                    null);
        }
    }

    private Map<String, Object> shipmentPayload(BigDecimal weight) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("pieces", 1);
        shipment.put("weight", weight);
        shipment.put("chargeableWeight", weight);
        shipment.put("volume", Math.max(0.001d, weight.doubleValue() / 167d));
        shipment.put("product", "GCR");
        shipment.put("measurementUnit", "METRIC");
        shipment.put("dimensions", List.of(Map.of(
                "pieces", 1,
                "length", 100,
                "width", 100,
                "height", 100,
                "weight", weight,
                "loadType", "DIMENSIONS",
                "weightType", "PER_ITEM",
                "stackable", true,
                "tiltable", false,
                "toploadable", false)));
        return shipment;
    }

    private Map<String, Object> userPayload() {
        Map<String, Object> user = new LinkedHashMap<>();
        if (!email.isBlank()) {
            user.put("email", email);
        }
        if (!iata.isBlank()) {
            user.put("iata", iata);
        }
        if (!cass.isBlank()) {
            user.put("cass", cass);
        }
        user.put("country", country);
        user.put("companyName", companyName);
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        return user;
    }

    private JsonNode chooseBestRate(JsonNode rates) {
        JsonNode best = null;
        for (JsonNode rate : rates) {
            if (rate.path("isManualRate").asBoolean(false)) {
                continue;
            }
            BigDecimal total = decimal(rate, "total");
            if (best == null || total.compareTo(decimal(best, "total")) < 0) {
                best = rate;
            }
        }
        return best;
    }

    private void validateSearch(
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {

        if (blank(origin) || !origin.trim().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("Origin must be a 3-letter airport code");
        }
        if (blank(destination) || !destination.trim().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("Destination must be a 3-letter airport code");
        }
        if (origin.trim().equalsIgnoreCase(destination.trim())) {
            throw new IllegalArgumentException("Origin and destination must differ");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("A valid search time window is required");
        }
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new IllegalArgumentException("Cargo weight must be greater than zero");
        }
    }

    private void validateBooking(BookingCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Booking command is required");
        }
        if (blank(command.carrierCode())) {
            throw new IllegalArgumentException("Carrier code is required");
        }
        if (blank(command.flightNumber())) {
            throw new IllegalArgumentException("Flight number is required");
        }
        if (blank(command.originCode()) || blank(command.destinationCode())) {
            throw new IllegalArgumentException("Origin and destination are required");
        }
        if (command.departureTime() == null) {
            throw new IllegalArgumentException("Departure time is required");
        }
        if (command.weightKg() == null || command.weightKg().signum() <= 0) {
            throw new IllegalArgumentException("Cargo weight must be greater than zero");
        }
    }

    private String inferBookingStatus(JsonNode root) {
        if (hasEventCode(root, "CNL")) {
            return "CANCELLED";
        }
        if (hasEventCode(root, "DLV")) {
            return "DELIVERED";
        }
        if (hasEventCode(root, "ARR")) {
            return "ARRIVED";
        }
        if (hasEventCode(root, "DEP")) {
            return "CONFIRMED";
        }
        return "PENDING";
    }

    private boolean hasEventCode(JsonNode root, String target) {
        EventSummary summary = summarizeEvents(root);
        return target.equalsIgnoreCase(
                "CNL".equals(target) ? ("CANCELLED".equals(summary.status()) ? "CNL" : "")
                        : "DLV".equals(target) ? ("DELIVERED".equals(summary.status()) ? "DLV" : "")
                        : "ARR".equals(target) ? ("ARRIVED".equals(summary.status()) ? "ARR" : "")
                        : ("DEPARTED".equals(summary.status()) ? "DEP" : ""));
    }

    private String extractFlightUuid(String providerReference) {
        if (blank(providerReference)) {
            return "";
        }
        String reference = providerReference.trim();
        int pipe = reference.indexOf('|');
        if (pipe > 0) {
            reference = reference.substring(0, pipe);
        }
        return reference;
    }

    private String extractOfferReference(String providerReference) {
        return extractFlightUuid(providerReference);
    }

    private String extractRateReference(String providerReference) {
        if (blank(providerReference)) {
            return "";
        }
        int pipe = providerReference.indexOf('|');
        if (pipe < 0 || pipe == providerReference.length() - 1) {
            return "";
        }
        return providerReference.substring(pipe + 1).trim();
    }

    private static int delayMinutes(Instant scheduled, Instant estimated, Instant actual) {
        Instant effective = actual != null ? actual : estimated;
        if (scheduled == null || effective == null) {
            return 0;
        }
        long seconds = Duration.between(scheduled, effective).getSeconds();
        return (int) Math.max(0, Math.round(seconds / 60.0));
    }

    private static Instant firstNonNull(Instant first, Instant second) {
        return first != null ? first : second;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                return new BigDecimal(value.asText());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static BigDecimal bookableWeightAvailable(boolean available, BigDecimal weight) {
        return available ? weight : BigDecimal.ZERO;
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        BigDecimal value = firstDecimal(node, key);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal decimalOrDefault(JsonNode node, String key, BigDecimal fallback) {
        BigDecimal value = firstDecimal(node, key);
        return value == null ? fallback : value;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText("");
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static Instant firstInstant(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            Instant value = parseInstant(firstText(node, key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        if (blank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static JsonNode firstObject(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode candidate = node.path(name);
            if (candidate.isObject()) {
                return candidate;
            }
            if (candidate.isArray() && !candidate.isEmpty() && candidate.get(0).isObject()) {
                return candidate.get(0);
            }
        }
        return null;
    }

    private static JsonNode firstArrayElement(JsonNode node) {
        if (node == null) {
            return null;
        }
        return node.isArray() && !node.isEmpty() ? node.get(0) : node;
    }

    private static void addArrayIfPresent(List<JsonNode> arrays, JsonNode node, String key) {
        if (node == null) {
            return;
        }
        JsonNode value = node.path(key);
        if (value.isArray()) {
            arrays.add(value);
        }
    }

    private record EventSummary(
            String status,
            String eventId,
            Instant actualDeparture,
            Instant actualArrival) {
    }

    private static String normalizeAirport(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return !blank(first) ? first.trim() : trim(second);
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String normalizePath(String value, String fallback) {
        String path = blank(value) ? fallback : value.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    private static String strip(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException(
                    "CargoAi integration is not configured: "
                            + String.join("; ", configurationIssues()));
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String safeErrorBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        return body.length() > 2000 ? body.substring(0, 2000) : body;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CargoAi search retry interrupted", ex);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
