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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Direct Qatar Airways Cargo Availability API adapter.
 *
 * <p>The public Qatar Cargo developer material documents an Availability & Rate
 * API with origin/destination, agent details, product/commodity and shipment
 * quantity inputs and flight/rate output. The carrier's Booking API exists as a
 * separate authenticated capability; AAL deliberately does not invent its
 * private booking payload. Once Qatar provisions the current booking contract
 * for the tenant, booking can be enabled without changing the domain contract.</p>
 */
@Component
public class QatarCargoProvider implements AirCargoProviderPort {
    private static final String CODE = "QATAR";
    private static final ZoneId QATAR_ZONE = ZoneId.of("Asia/Qatar");

    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final CircuitBreakerService circuitBreaker;
    private final ProviderRateLimiter rateLimiter;
    private final IntegrationMetricsService metrics;

    private final boolean enabled;
    private final String baseUrl;
    private final String availabilityPath;
    private final String authHeader;
    private final String authValue;
    private final String authPrefix;
    private final String accountNumber;
    private final String agentIata;
    private final String agentCass;
    private final String customerName;
    private final String contactEmail;
    private final String productCode;
    private final String serviceCode;
    private final String commodityCode;
    private final int searchDays;

    public QatarCargoProvider(
            RestTemplate rest,
            ObjectMapper mapper,
            @Value("${aircargo.qatar.enabled:false}") boolean enabled,
            @Value("${aircargo.qatar.base-url:}") String baseUrl,
            @Value("${aircargo.qatar.availability-path:/availability}") String availabilityPath,
            @Value("${aircargo.qatar.auth-header:X-API-Key}") String authHeader,
            @Value("${aircargo.qatar.auth-value:}") String authValue,
            @Value("${aircargo.qatar.auth-prefix:}") String authPrefix,
            @Value("${aircargo.qatar.account-number:}") String accountNumber,
            @Value("${aircargo.qatar.agent-iata:}") String agentIata,
            @Value("${aircargo.qatar.agent-cass:}") String agentCass,
            @Value("${aircargo.qatar.customer-name:Aviation Africa Logistics Ltd}") String customerName,
            @Value("${aircargo.qatar.contact-email:}") String contactEmail,
            @Value("${aircargo.qatar.product-code:GCR}") String productCode,
            @Value("${aircargo.qatar.service-code:}") String serviceCode,
            @Value("${aircargo.qatar.commodity-code:}") String commodityCode,
            @Value("${aircargo.qatar.search-days:5}") int searchDays,
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
        this.availabilityPath = normalizePath(availabilityPath, "/availability");
        this.authHeader = trim(authHeader).isBlank() ? "X-API-Key" : trim(authHeader);
        this.authValue = trim(authValue);
        this.authPrefix = trim(authPrefix);
        this.accountNumber = trim(accountNumber);
        this.agentIata = trim(agentIata);
        this.agentCass = trim(agentCass);
        this.customerName = trim(customerName);
        this.contactEmail = trim(contactEmail);
        this.productCode = trim(productCode).isBlank() ? "GCR" : trim(productCode);
        this.serviceCode = trim(serviceCode);
        this.commodityCode = trim(commodityCode);
        this.searchDays = Math.max(1, Math.min(14, searchDays));
    }

    @Override
    public String providerCode() {
        return CODE;
    }

    @Override
    public boolean configured() {
        return enabled
                && !baseUrl.isBlank()
                && !authValue.isBlank()
                && !agentIata.isBlank()
                && !agentCass.isBlank()
                && !commodityCode.isBlank();
    }

    @Override
    public List<String> configurationIssues() {
        List<String> issues = new ArrayList<>();
        if (!enabled) issues.add("AIRCARGO_QATAR_ENABLED is false");
        if (baseUrl.isBlank()) issues.add("AIRCARGO_QATAR_BASE_URL is missing");
        if (authValue.isBlank()) issues.add("AIRCARGO_QATAR_API_KEY is missing");
        if (agentIata.isBlank()) issues.add("AIRCARGO_QATAR_AGENT_IATA is missing");
        if (agentCass.isBlank()) issues.add("AIRCARGO_QATAR_AGENT_CASS is missing");
        if (commodityCode.isBlank()) issues.add("AIRCARGO_QATAR_COMMODITY_CODE is missing");
        return issues;
    }

    @Override
    public ProviderCapabilities capabilities() {
        boolean on = configured();
        return new ProviderCapabilities(
                on,     // Availability & Rate search
                on,     // capacity represented by returned availability snapshot
                false,  // Booking stays disabled until Qatar provisions exact tenant booking contract
                false,
                false,
                false,
                false,
                false,
                false,
                on,
                List.of("QATAR_CARGO_AVAILABILITY_API"));
    }

    @Override
    public List<FlightOffer> searchFlights(
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {

        requireConfigured();
        if (origin == null || !origin.trim().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("Qatar Cargo origin must be a 3-letter airport code");
        }
        if (destination == null || !destination.trim().matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("Qatar Cargo destination must be a 3-letter airport code");
        }
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new IllegalArgumentException("weightKg must be positive");
        }

        LocalDate fromDate = from == null ? LocalDate.now(QATAR_ZONE) : from.atZone(QATAR_ZONE).toLocalDate();
        LocalDate toDate = to == null ? fromDate.plusDays(searchDays - 1L) : to.atZone(QATAR_ZONE).toLocalDate();
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("Qatar Availability search end date must not precede start date");
        }
        int requestedDays = Math.max(1, Math.min(searchDays, (int) (toDate.toEpochDay() - fromDate.toEpochDay() + 1)));

        Map<String, Object> shipmentQuantity = new LinkedHashMap<>();
        shipmentQuantity.put("chargeableWeight", number(weightKg));
        shipmentQuantity.put("totalPieces", 1);
        shipmentQuantity.put("totalWeight", number(weightKg));
        shipmentQuantity.put("weightUnit", "KG");

        Map<String, Object> customer = new LinkedHashMap<>();
        if (!accountNumber.isBlank()) customer.put("accountNumber", accountNumber);
        customer.put("agentIataCode", agentIata);
        customer.put("agentCassCode", agentCass);
        if (!contactEmail.isBlank()) customer.put("contactDetails", Map.of("emailAddress", contactEmail));
        if (!customerName.isBlank()) customer.put("customerName", customerName);

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("productCode", productCode);
        if (!serviceCode.isBlank()) product.put("serviceCode", serviceCode);
        product.put("commodityCode", commodityCode);

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("directFlight", false);
        routing.put("excludeTrucking", true);
        routing.put("fetchAvailForNextXDays", requestedDays);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerDetails", customer);
        body.put("origin", origin.trim().toUpperCase(Locale.ROOT));
        body.put("originType", "Airport");
        body.put("destination", destination.trim().toUpperCase(Locale.ROOT));
        body.put("destinationType", "Airport");
        body.put("productDetails", product);
        body.put("routingPreference", routing);
        body.put("shipmentQuantityDetails", shipmentQuantity);
        body.put("scheduledDepartureDate", fromDate.toString());

        long started = System.nanoTime();
        try {
            rateLimiter.acquire(CODE);
            if (!circuitBreaker.allow(CODE)) {
                throw new ExternalOperationException("PROVIDER_CIRCUIT_OPEN", null, false, 503);
            }

            ResponseEntity<String> response = rest.exchange(
                    buildUrl(),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers()),
                    String.class);
            JsonNode root = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            List<FlightOffer> offers = parseOffers(root, origin, destination, weightKg);
            long latency = (System.nanoTime() - started) / 1_000_000L;
            circuitBreaker.success(CODE, latency, response.getStatusCode().value());
            metrics.providerSuccess(CODE, latency);
            return offers;
        } catch (HttpStatusCodeException ex) {
            long latency = (System.nanoTime() - started) / 1_000_000L;
            circuitBreaker.failure(CODE, latency, ex.getStatusCode().value());
            metrics.providerFailure(CODE, latency, "HTTP_" + ex.getStatusCode().value());
            throw new ExternalOperationException(
                    "QATAR_AVAILABILITY_HTTP_" + ex.getStatusCode().value() + ": " + responseBody(ex),
                    ex,
                    false,
                    ex.getStatusCode().value());
        } catch (ExternalOperationException ex) {
            long latency = (System.nanoTime() - started) / 1_000_000L;
            metrics.providerFailure(CODE, latency, "QATAR_PROVIDER_ERROR");
            throw ex;
        } catch (Exception ex) {
            long latency = (System.nanoTime() - started) / 1_000_000L;
            circuitBreaker.failure(CODE, latency, null);
            metrics.providerFailure(CODE, latency, ex.getClass().getSimpleName());
            throw new ExternalOperationException("QATAR_AVAILABILITY_FAILED", ex, true, null);
        }
    }

    @Override
    public CapacitySnapshot capacity(String flightNumber, Instant date) {
        throw new UnsupportedOperationException(
                "Qatar Cargo exposes capacity through Availability & Rate results; call live search instead");
    }

    @Override
    public BookingResult book(BookingCommand command) {
        throw new UnsupportedOperationException(
                "Qatar Cargo Booking API is not enabled until the tenant's current private booking contract and endpoint are provisioned");
    }

    @Override
    public BookingResult getBooking(String providerReference) {
        throw new UnsupportedOperationException("Qatar Cargo direct booking lookup is not configured");
    }

    @Override
    public BookingResult amend(AmendmentCommand command) {
        throw new UnsupportedOperationException("Qatar Cargo direct booking amendment is not configured");
    }

    @Override
    public BookingResult cancel(CancellationCommand command) {
        throw new UnsupportedOperationException("Qatar Cargo direct booking cancellation is not configured");
    }

    @Override
    public FlightStatus getFlightStatus(String flightNumber, String flightDate) {
        throw new UnsupportedOperationException("Qatar Cargo direct flight tracking is not configured");
    }

    @Override
    public AwbSubmissionResult submitAwb(Map<String, Object> payload, String idempotencyKey) {
        throw new UnsupportedOperationException("Qatar Cargo FWB/FHL submission requires its separate messaging contract");
    }

    private List<FlightOffer> parseOffers(
            JsonNode root,
            String requestedOrigin,
            String requestedDestination,
            BigDecimal requestedWeight) {
        List<FlightOffer> out = new ArrayList<>();
        JsonNode responseSets = root.path("availabilityResponseSOs");
        if (!responseSets.isArray()) {
            responseSets = root.path("data").path("availabilityResponseSOs");
        }
        if (!responseSets.isArray() && root.has("flightItineraries")) {
            responseSets = root;
        }
        if (!responseSets.isArray()) {
            return out;
        }

        for (JsonNode responseSet : responseSets) {
            String requestRef = firstText(responseSet, "requestRefId", "requestReferenceId", "referenceId");
            JsonNode itineraries = responseSet.path("flightItineraries");
            if (!itineraries.isArray()) {
                continue;
            }
            for (JsonNode itinerary : itineraries) {
                List<JsonNode> rates = nodes(itinerary.path("rateDetails"));
                if (rates.isEmpty()) {
                    rates = List.of((JsonNode) null);
                }
                for (JsonNode rate : rates) {
                    JsonNode rateNode = rate == null ? itinerary : rate;
                    String flightNumber = firstText(itinerary, "carrierNumber", "flightNumber", "flightNo");
                    if (flightNumber.isBlank()) continue;
                    String carrierCode = firstText(itinerary, "carrierCode", "marketingCarrier", "carrier");
                    if (carrierCode.isBlank()) carrierCode = "QR";
                    String carrierName = firstText(itinerary, "carrierName", "carrierDisplayName");
                    if (carrierName.isBlank()) carrierName = "Qatar Airways Cargo";

                    Instant departure = parseProviderTime(
                            firstText(itinerary, "scheduledDepartureDate"),
                            firstText(itinerary, "scheduledDepartureTime", "departureTime", "scheduledDeparture"));
                    Instant arrival = parseProviderTime(
                            firstText(itinerary, "scheduledArrivalDate"),
                            firstText(itinerary, "scheduledArrivalTime", "arrivalTime", "scheduledArrival"));
                    if (departure == null || arrival == null || !arrival.isAfter(departure)) continue;

                    String rateId = firstText(rateNode, "rateId", "rateReference", "rateCode", "rateKey");
                    String rateName = firstText(rateNode, "rateName", "rateType", "productCode");
                    String currency = firstText(rateNode, "currencyCode", "currency");
                    BigDecimal totalPrice = firstDecimal(rateNode, "totalAmount", "discountedtotalAmount", "discountedTotalAmount", "amount");
                    BigDecimal unitPrice = firstDecimal(rateNode, "ratePerKilo", "ratePerKiloAmount", "unitPrice");

                    BigDecimal availability = firstDecimal(
                            itinerary,
                            "availableWeight", "availableCapacityKg", "availableCapacity",
                            "remainingWeight", "remainingCapacityKg");
                    BigDecimal totalCapacity = firstDecimal(
                            itinerary,
                            "totalCapacityKg", "totalCapacity", "capacityKg", "maximumWeight");
                    String flightStatus = firstText(itinerary, "bookingStatus", "flightBookingStatus", "status");
                    boolean open = isOpenStatus(flightStatus);

                    String providerReference = joinRef(
                            requestRef,
                            flightNumber,
                            rateId);
                    String reason;
                    boolean bookable = false;
                    if (!open) {
                        reason = flightStatus.isBlank() ? "Qatar Availability returned no open booking status" : "Qatar booking status: " + flightStatus;
                    } else if (rateId.isBlank()) {
                        reason = "Qatar Availability returned no rate identifier";
                    } else {
                        reason = "Live Qatar Cargo availability/rate result; direct booking is enabled after tenant booking API provisioning";
                    }

                    // Some Availability responses contain no standalone capacity amount.
                    // Keep a safe minimum snapshot so AAL can display and filter the offer,
                    // while clearly marking that the figure is request-weight based.
                    if (availability == null || availability.signum() < 0) availability = requestedWeight;
                    if (totalCapacity == null || totalCapacity.compareTo(availability) < 0) totalCapacity = availability;
                    if (totalCapacity.signum() == 0) totalCapacity = requestedWeight;
                    if (availability.signum() == 0) availability = requestedWeight.min(totalCapacity);

                    out.add(new FlightOffer(
                            carrierCode,
                            carrierName,
                            flightNumber,
                            requestedOrigin.trim().toUpperCase(Locale.ROOT),
                            requestedDestination.trim().toUpperCase(Locale.ROOT),
                            departure,
                            arrival,
                            totalCapacity.setScale(3, RoundingMode.HALF_UP),
                            availability.setScale(3, RoundingMode.HALF_UP),
                            "QATAR_CARGO",
                            open ? "AVAILABLE" : "CLOSED",
                            providerReference,
                            requestRef,
                            rateId,
                            rateId,
                            rateName,
                            currency,
                            totalPrice,
                            unitPrice,
                            firstText(rateNode, "productCode", "product"),
                            bookable,
                            reason));
                }
            }
        }
        return out;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        String value = authPrefix.isBlank() ? authValue : authPrefix + " " + authValue;
        if (!value.isBlank()) h.set(authHeader, value);
        return h;
    }

    private String buildUrl() {
        String left = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return left + availabilityPath;
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("Qatar Cargo integration is not configured: " + String.join("; ", configurationIssues()));
        }
    }

    private static List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return result;
        if (node.isArray()) node.forEach(result::add);
        else result.add(node);
        return result;
    }

    private static String joinRef(String requestRef, String flightNumber, String rateId) {
        return String.join("|", clean(requestRef), clean(flightNumber), clean(rateId));
    }

    private static boolean isOpenStatus(String status) {
        if (status == null || status.isBlank()) return true;
        String s = status.trim().toUpperCase(Locale.ROOT);
        return s.equals("OPEN") || s.equals("AVAILABLE") || s.equals("BOOKABLE") || s.equals("CONFIRMED") || s.equals("OK");
    }

    private static BigDecimal firstDecimal(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            try {
                if (value.isNumber()) return value.decimalValue();
                String text = value.asText().trim().replace(",", "");
                if (!text.isBlank()) return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        if (node == null) return "";
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return "";
    }

    private static Instant parseProviderTime(String date, String time) {
        if (date == null || date.isBlank()) return parseInstant(time);
        if (time == null || time.isBlank()) return parseInstant(date);
        try {
            return Instant.parse(date + "T" + time);
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate d = LocalDate.parse(date);
            String cleanTime = time.length() >= 8 ? time.substring(0, 8) : time;
            LocalTime t = LocalTime.parse(cleanTime);
            return d.atTime(t).atZone(QATAR_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            return parseInstant(date);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Number number(BigDecimal v) {
        return v.stripTrailingZeros();
    }

    private static String responseBody(HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString();
        return body == null ? ex.getStatusText() : body;
    }

    private static String normalizePath(String value, String fallback) {
        String p = trim(value);
        if (p.isBlank()) p = fallback;
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
