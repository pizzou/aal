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
 * First-class CargoAi CargoCONNECT adapter.
 *
 * <p>CargoAi live offers are normalized into AAL's twelve-field
 * {@link FlightOffer} contract. Because that contract has no separate rate
 * field, the exact CargoAi selection is carried in {@code providerReference}
 * as {@code flightUUID|rateId}. The reference is persisted with the selected
 * flight and passed through the booking command so the booking operation uses
 * the exact offer returned by the live search.</p>
 */
@Component
public class CargoAiAirCargoProvider implements AirCargoProviderPort {

    private static final String BASE_URL = "https://api.cargoai.co/solutions";
    private static final String PROVIDER_CODE = "CARGOAI";

    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String email;
    private final String iata;
    private final String cass;
    private final String country;
    private final String companyName;
    private final String firstName;
    private final String lastName;
    private final CircuitBreakerService circuitBreaker;
    private final ProviderRateLimiter rateLimiter;
    private final IntegrationMetricsService metrics;

    public CargoAiAirCargoProvider(
            RestTemplate rest,
            ObjectMapper mapper,
            @Value("${aircargo.cargoai.api-key:${AIRCARGO_CARGOAI_API_KEY:}}") String apiKey,
            @Value("${aircargo.cargoai.user.email:${AIRCARGO_CARGOAI_USER_EMAIL:}}") String email,
            @Value("${aircargo.cargoai.user.iata:${AIRCARGO_CARGOAI_USER_IATA:}}") String iata,
            @Value("${aircargo.cargoai.user.cass:${AIRCARGO_CARGOAI_USER_CASS:}}") String cass,
            @Value("${aircargo.cargoai.user.country:${AIRCARGO_CARGOAI_USER_COUNTRY:RW}}") String country,
            @Value("${aircargo.cargoai.user.company-name:${AIRCARGO_CARGOAI_COMPANY_NAME:AAL}}") String companyName,
            @Value("${aircargo.cargoai.user.first-name:${AIRCARGO_CARGOAI_USER_FIRST_NAME:AAL}}") String firstName,
            @Value("${aircargo.cargoai.user.last-name:${AIRCARGO_CARGOAI_USER_LAST_NAME:Operations}}") String lastName,
            CircuitBreakerService circuitBreaker,
            ProviderRateLimiter rateLimiter,
            IntegrationMetricsService metrics) {
        this.rest = rest;
        this.mapper = mapper;
        this.apiKey = trim(apiKey);
        this.email = trim(email);
        this.iata = trim(iata);
        this.cass = trim(cass);
        this.country = blank(country) ? "RW" : country.trim().toUpperCase(Locale.ROOT);
        this.companyName = blank(companyName) ? "AAL" : companyName.trim();
        this.firstName = blank(firstName) ? "AAL" : firstName.trim();
        this.lastName = blank(lastName) ? "Operations" : lastName.trim();
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    public boolean configured() {
        return !apiKey.isBlank()
                && (!email.isBlank() || (!iata.isBlank() && !cass.isBlank()));
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public ProviderCapabilities capabilities() {
        boolean on = configured();
        return new ProviderCapabilities(
                on,
                on,
                on,
                false,
                false,
                false,
                false,
                false,
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
        BigDecimal weight = weightKg.max(BigDecimal.ZERO);

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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", normalizedOrigin);
        body.put("destination", normalizedDestination);
        body.put("departureDate", LocalDate.ofInstant(from, ZoneOffset.UTC).toString());

        long days = Math.max(0, Duration.between(from, to).toHours() / 24);
        body.put("offset", Math.min(5, (int) Math.ceil(days)));
        body.put("timeout", 25);
        body.put("shipment", shipment);
        body.put("user", user());
        body.put("filters", Map.of("withRateOnly", true, "liveRequests", true));

        JsonNode root = post("/search", body);
        JsonNode flights = root.path("flights");
        if (!flights.isArray()) {
            return List.of();
        }

        List<FlightOffer> result = new ArrayList<>();

        for (JsonNode flight : flights) {
            boolean available = flight.path("available").asBoolean(false);
            boolean providerBookable = flight.path("features").path("bookable").asBoolean(false);

            String flightUuid = firstText(flight, "flightUUID", "flightUID", "id");
            String carrier = firstText(flight, "airlineCode", "carrierCode");
            String carrierName = firstText(flight, "airlineName", "carrierName", "airline");
            String number = firstText(flight, "flightNumber", "flightNo");
            Instant departure = firstInstant(flight, "departureTime", "departure");
            Instant arrival = firstInstant(flight, "arrivalTime", "arrival");

            if (blank(flightUuid) || blank(carrier) || blank(number) || departure == null) {
                continue;
            }

            /*
             * The AAL contract has no rate/price fields and CargoAi booking
             * requires a rateId. Never expose a flight without a concrete
             * live rate because it cannot be booked safely later.
             */
            JsonNode rates = flight.path("rates");
            if (!rates.isArray() || rates.isEmpty() || !available || !providerBookable) {
                continue;
            }

            JsonNode best = null;
            for (JsonNode rate : rates) {
                if (rate.path("isManualRate").asBoolean(false)) {
                    continue;
                }
                BigDecimal total = decimal(rate, "total");
                if (total == null || total.signum() < 0) {
                    continue;
                }
                if (best == null || total.compareTo(decimal(best, "total")) < 0) {
                    best = rate;
                }
            }

            if (best == null) {
                continue;
            }

            String rateId = firstText(best, "id", "rateId");
            if (blank(rateId)) {
                continue;
            }

            String rateName = firstText(best, "name", "type", "product");
            String selection = buildProviderReference(flightUuid, rateId);
            if (blank(selection)) {
                continue;
            }

            /*
             * CargoAi does not provide a normalized total-capacity value in
             * the AAL contract. For a live rate offer, the requested weight is
             * the amount confirmed as available for this search.
             */
            result.add(new FlightOffer(
                    carrier,
                    blank(carrierName) ? carrier : carrierName,
                    number,
                    normalizedOrigin,
                    normalizedDestination,
                    departure,
                    arrival,
                    weight,
                    weight,
                    blank(rateName) ? "LIVE" : rateName,
                    "LIVE",
                    selection));
        }

        return result;
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

        String providerReference = trim(command.providerReference());
        if (blank(providerReference) || !providerReference.contains("|")) {
            throw new IllegalArgumentException(
                    "A live CargoAi flight and rate selection is required before booking");
        }

        String[] references = providerReference.split("\\|", 2);
        String flightUuid = references.length > 0 ? trim(references[0]) : "";
        String rateId = references.length > 1 ? trim(references[1]) : "";

        if (blank(flightUuid) || blank(rateId)) {
            throw new IllegalArgumentException("Invalid CargoAi live offer reference");
        }

        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("pieces", 1);
        shipment.put("weight", command.weightKg());
        shipment.put("chargeableWeight", command.weightKg());
        shipment.put("volume", Math.max(0.001d, command.weightKg().doubleValue() / 167d));
        shipment.put("product", "GCR");
        shipment.put("measurementUnit", "METRIC");

        Map<String, Object> itinerary = new LinkedHashMap<>();
        itinerary.put("origin", command.originCode());
        itinerary.put("destination", command.destinationCode());
        itinerary.put("airlineCode", command.carrierCode());
        itinerary.put("departureDates", List.of(command.departureTime().toString().substring(0, 10)));
        itinerary.put("flightNumbers", List.of(command.flightNumber()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightUUID", flightUuid);
        body.put("rateId", rateId);
        body.put("user", user());
        body.put("shipment", shipment);
        body.put("itinerary", itinerary);
        body.put("comment", "AAL shipment " + safe(command.shipmentId()));

        JsonNode root = post("/book", body);

        String status = firstText(root, "bookingStatus", "status");
        status = blank(status) ? "PENDING" : status.toUpperCase(Locale.ROOT);

        String awb = firstText(root, "awb", "awbNumber", "airWaybill");
        String reference = blank(awb)
                ? firstText(root, "bookingReference", "reference", "id", "flightUUID")
                : awb;
        String confirmation = blank(awb)
                ? firstText(root, "confirmationNumber", "confirmation", "id", "bookingReference", reference)
                : awb;

        return new BookingResult(
                status,
                reference,
                confirmation,
                decimalOrDefault(root, "confirmedWeightKg", command.weightKg()),
                root.toString());
    }

    @Override
    public BookingResult getBooking(String providerReference) {
        throw new UnsupportedOperationException(
                "CargoAi booking reconciliation requires the provider callback/booking tracking integration");
    }

    @Override
    public BookingResult amend(AmendmentCommand command) {
        throw new UnsupportedOperationException(
                "CargoAi Quote & Book bookings are cancelled and rebooked rather than amended");
    }

    @Override
    public BookingResult cancel(CancellationCommand command) {
        throw new UnsupportedOperationException(
                "CargoAi cancellation is not enabled in this adapter");
    }

    @Override
    public FlightStatus getFlightStatus(String flightNumber, String flightDate) {
        throw new UnsupportedOperationException(
                "CargoAi Track & Trace is a separate integration");
    }

    @Override
    public AwbSubmissionResult submitAwb(Map<String, Object> payload, String idempotencyKey) {
        throw new UnsupportedOperationException(
                "CargoAi eAWB is a separate integration");
    }

    private Map<String, Object> user() {
        Map<String, Object> user = new LinkedHashMap<>();
        if (!email.isBlank()) user.put("email", email);
        if (!iata.isBlank()) user.put("iata", iata);
        if (!cass.isBlank()) user.put("cass", cass);
        user.put("country", country);
        user.put("companyName", companyName);
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        return user;
    }

    private JsonNode post(String path, Object body) {
        if (!circuitBreaker.allow(providerCode())) {
            throw new ExternalOperationException(
                    "CargoAi circuit breaker is OPEN", null, true, null);
        }

        rateLimiter.acquire(providerCode());
        long started = System.nanoTime();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("x-api-key", apiKey);

            ResponseEntity<String> response = rest.exchange(
                    BASE_URL + path,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);

            long latency = elapsedMillis(started);
            int httpStatus = response.getStatusCode().value();

            circuitBreaker.success(providerCode(), latency, httpStatus);
            metrics.providerSuccess(providerCode(), latency);

            String responseBody = response.getBody();
            return mapper.readTree(
                    responseBody == null || responseBody.isBlank() ? "{}" : responseBody);

        } catch (HttpStatusCodeException ex) {
            long latency = elapsedMillis(started);
            int httpStatus = ex.getStatusCode().value();
            String code = Integer.toString(httpStatus);

            circuitBreaker.failure(providerCode(), latency, httpStatus);
            metrics.providerFailure(providerCode(), latency, code);

            boolean retryable = ex.getStatusCode().is5xxServerError()
                    || httpStatus == 408
                    || httpStatus == 429;

            throw new ExternalOperationException(
                    "CargoAi API error: " + safeErrorBody(ex.getResponseBodyAsString()),
                    ex,
                    retryable,
                    httpStatus);

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
                    "CargoAi API connection failed: " + ex.getMessage(),
                    ex,
                    true,
                    null);
        }
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException(
                    "CargoAi integration is not configured: API key and user email or IATA/CASS are required");
        }
    }

    private static void validateSearch(
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

    private static void validateBooking(BookingCommand command) {
        if (command == null) throw new IllegalArgumentException("Booking command is required");
        if (blank(command.carrierCode())) throw new IllegalArgumentException("Carrier code is required");
        if (blank(command.flightNumber())) throw new IllegalArgumentException("Flight number is required");
        if (blank(command.originCode()) || blank(command.destinationCode())) {
            throw new IllegalArgumentException("Origin and destination are required");
        }
        if (command.departureTime() == null) throw new IllegalArgumentException("Departure time is required");
        if (command.weightKg() == null || command.weightKg().signum() <= 0) {
            throw new IllegalArgumentException("Cargo weight must be greater than zero");
        }
        if (blank(command.providerReference())) {
            throw new IllegalArgumentException("CargoAi provider reference is required for live booking");
        }
    }

    private static String buildProviderReference(String flightUuid, String rateId) {
        if (blank(flightUuid) || blank(rateId)) return "";
        return flightUuid.trim() + "|" + rateId.trim();
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || keys == null) return "";
        for (String key : keys) {
            String value = text(node, key, null);
            if (!blank(value)) return value;
        }
        return "";
    }

    private static Instant firstInstant(JsonNode node, String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            Instant value = instant(node, key);
            if (value != null) return value;
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        String value = text(node, key, null);
        if (blank(value)) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal decimalOrDefault(JsonNode node, String key, BigDecimal fallback) {
        BigDecimal value = decimal(node, key);
        return value == null ? fallback : value;
    }

    private static Instant instant(JsonNode node, String key) {
        String value = text(node, key, null);
        if (blank(value)) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static String text(JsonNode node, String key, String fallback) {
        if (node == null || key == null) return fallback;
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String safeErrorBody(String body) {
        if (body == null || body.isBlank()) return "empty response";
        return body.length() > 2000 ? body.substring(0, 2000) : body;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
