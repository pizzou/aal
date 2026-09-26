package com.logiplatform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Normalized HTTP adapter. It deliberately does not pretend to be an airline
 * integration by itself: production booking becomes active only when an airline,
 * cargo platform or community system supplies its endpoint and credentials.
 * Provider-specific adapters can replace this bean without changing the domain.
 */
@Component
public class GenericHttpAirCargoProvider implements AirCargoProviderPort {
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final String enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String schedulesPath;
    private final String bookingPath;
    private final String cancelPath;
    private final String amendPath;
    private final String statusPath;
    private final String awbPath;
    private final List<String> standards;
    private final String providerCode;
    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt;

    public GenericHttpAirCargoProvider(
            RestTemplate rest,
            ObjectMapper mapper,
            @Value("${aircargo.provider.enabled:false}") boolean enabled,
            @Value("${aircargo.provider.base-url:}") String baseUrl,
            @Value("${aircargo.provider.api-key:}") String apiKey,
            @Value("${aircargo.provider.oauth2.token-url:}") String tokenUrl,
            @Value("${aircargo.provider.oauth2.client-id:}") String clientId,
            @Value("${aircargo.provider.oauth2.client-secret:}") String clientSecret,
            @Value("${aircargo.provider.code:GENERIC_HTTP}") String providerCode,
            @Value("${aircargo.provider.schedules-path:/schedules}") String schedulesPath,
            @Value("${aircargo.provider.booking-path:/bookings}") String bookingPath,
            @Value("${aircargo.provider.cancel-path:/bookings}") String cancelPath,
            @Value("${aircargo.provider.amend-path:/bookings}") String amendPath,
            @Value("${aircargo.provider.status-path:/flights/status}") String statusPath,
            @Value("${aircargo.provider.awb-path:/awb}") String awbPath,
            @Value("${aircargo.provider.standards:GENERIC_JSON}") String standards) {
        this.rest = rest;
        this.mapper = mapper;
        this.enabled = enabled ? "true" : "false";
        this.baseUrl = strip(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.tokenUrl = strip(tokenUrl);
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.providerCode = providerCode == null || providerCode.isBlank() ? "GENERIC_HTTP" : providerCode.trim().toUpperCase();
        this.schedulesPath = schedulesPath;
        this.bookingPath = bookingPath;
        this.cancelPath = cancelPath;
        this.amendPath = amendPath;
        this.statusPath = statusPath;
        this.awbPath = awbPath;
        this.standards = standards == null ? List.of("GENERIC_JSON") : Arrays.stream(standards.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
    }

    public boolean configured() { return "true".equalsIgnoreCase(enabled) && !baseUrl.isBlank(); }

    @Override public String providerCode() { return providerCode; }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(configured(), configured(), configured(), configured(), configured(), configured(), configured(), configured(), !tokenUrl.isBlank(), !apiKey.isBlank(), standards);
    }

    @Override
    public List<FlightOffer> searchFlights(String origin, String destination, Instant from, Instant to, BigDecimal weightKg) {
        requireConfigured();
        String url = baseUrl + path(schedulesPath) + "?origin=" + enc(origin) + "&destination=" + enc(destination)
                + "&from=" + enc(from.toString()) + "&to=" + enc(to.toString()) + "&weightKg=" + enc(weightKg.toPlainString());
        JsonNode root = getJson(url);
        JsonNode items = root.has("flights") ? root.get("flights") : root.has("data") ? root.get("data") : root;
        if (!items.isArray()) throw new IllegalStateException("Provider schedule response must contain an array");
        List<FlightOffer> result = new ArrayList<>();
        for (JsonNode n : items) {
            String carrier = text(n, "carrierCode");
            String number = text(n, "flightNumber");
            String o = text(n, "originCode", text(n, "origin", origin));
            String d = text(n, "destinationCode", text(n, "destination", destination));
            Instant dep = instant(n, "departureTime", "departure");
            Instant arr = instant(n, "arrivalTime", "arrival");
            BigDecimal total = decimal(n, "totalCapacityKg");
            BigDecimal available = decimal(n, "availableCapacityKg");
            if (carrier.isBlank() || number.isBlank() || dep == null || total == null || available == null || available.signum() < 0 || available.compareTo(total) > 0)
                throw new IllegalStateException("Provider returned an invalid flight offer");
            if (available.compareTo(weightKg) < 0) continue;
            result.add(new FlightOffer(carrier, text(n, "carrierName", carrier), number, o, d, dep, arr, total, available,
                    text(n, "serviceLevel", "STANDARD"), text(n, "status", "SCHEDULED"), text(n, "providerReference", null)));
        }
        return result;
    }

    @Override
    public CapacitySnapshot capacity(String flightNumber, Instant date) {
        requireConfigured();
        String url = baseUrl + path("/capacity") + "?flightNumber=" + enc(flightNumber) + "&date=" + enc(date.toString());
        JsonNode n = getJson(url);
        BigDecimal available = decimal(n, "availableCapacityKg");
        BigDecimal total = decimal(n, "totalCapacityKg");
        if (available == null || total == null || available.signum() < 0 || total.signum() < 0 || available.compareTo(total) > 0)
            throw new IllegalStateException("Provider returned invalid capacity");
        return new CapacitySnapshot(flightNumber, available, total, Instant.now(), providerCode);
    }

    @Override
    public BookingResult book(BookingCommand c) { return booking(HttpMethod.POST, bookingPath, c.idempotencyKey(), commandMap(c), false); }

    @Override
    public BookingResult getBooking(String providerReference) {
        requireConfigured();
        JsonNode n = getJson(baseUrl + operationPath(bookingPath, providerReference));
        String status = text(n, "status", "UNKNOWN").toUpperCase(Locale.ROOT);
        String ref = text(n, "providerReference", text(n, "reference", providerReference));
        String confirmation = text(n, "confirmationNumber", text(n, "confirmation", ref));
        return new BookingResult(status, ref, confirmation, decimal(n, "confirmedWeightKg"), n.toString());
    }

    @Override
    public BookingResult amend(AmendmentCommand c) {
        String path = operationPath(amendPath, c.providerReference());
        return booking(HttpMethod.PATCH, path, c.idempotencyKey(), amendmentMap(c), false);
    }

    @Override
    public BookingResult cancel(CancellationCommand c) {
        String path = operationPath(cancelPath, c.providerReference()) + "/cancel";
        return booking(HttpMethod.POST, path, c.idempotencyKey(), Map.of("reason", c.reason() == null ? "OPERATIONAL_CHANGE" : c.reason()), true);
    }

    @Override
    public FlightStatus getFlightStatus(String flightNumber, String flightDate) {
        requireConfigured();
        String url = baseUrl + path(statusPath) + "?flightNumber=" + enc(flightNumber) + "&flightDate=" + enc(flightDate);
        JsonNode n = getJson(url);
        if (n.has("data") && n.get("data").isArray()) n = n.get("data").size() == 0 ? mapper.createObjectNode() : n.get("data").get(0);
        String status = text(n, "flightStatus", text(n, "status", "UNKNOWN"));
        return new FlightStatus(status,
                instant(n, "scheduledDeparture", "departureScheduled"), instant(n, "estimatedDeparture", "departureEstimated"), instant(n, "actualDeparture", "departureActual"),
                instant(n, "scheduledArrival", "arrivalScheduled"), instant(n, "estimatedArrival", "arrivalEstimated"), instant(n, "actualArrival", "arrivalActual"),
                intValue(n, "departureDelayMinutes"), intValue(n, "arrivalDelayMinutes"), text(n, "providerEventId", null), n.toString());
    }

    @Override
    public AwbSubmissionResult submitAwb(Map<String, Object> payload, String idempotencyKey) {
        requireConfigured();
        JsonNode n = request(HttpMethod.POST, awbPath, payload, idempotencyKey);
        String status = text(n, "status", "SUBMITTED");
        String ref = text(n, "providerReference", text(n, "reference", null));
        return new AwbSubmissionResult(status, ref, n.toString());
    }

    private BookingResult booking(HttpMethod method, String path, String key, Map<String,Object> payload, boolean cancellation) {
        requireConfigured();
        JsonNode n = request(method, path, payload, key);
        String status = text(n, "status", cancellation ? "CANCELLED" : "PENDING").toUpperCase(Locale.ROOT);
        String ref = text(n, "providerReference", text(n, "reference", null));
        String confirmation = text(n, "confirmationNumber", text(n, "confirmation", ref));
        BigDecimal weight = decimal(n, "confirmedWeightKg");
        return new BookingResult(status, ref, confirmation, weight, n.toString());
    }

    private JsonNode getJson(String url) { return request(HttpMethod.GET, url, null, UUID.randomUUID().toString()); }

    private JsonNode request(HttpMethod method, String pathOrUrl, Object payload, String idempotencyKey) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String url = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + path(pathOrUrl);
                HttpHeaders h = headers();
                if (idempotencyKey != null && !idempotencyKey.isBlank()) h.set("Idempotency-Key", idempotencyKey);
                HttpEntity<Object> entity = new HttpEntity<>(payload, h);
                ResponseEntity<String> response = rest.exchange(url, method, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful()) return parse(response.getBody());
                if (response.getStatusCode().is4xxClientError()) throw new IllegalStateException("Provider rejected request: HTTP " + response.getStatusCode().value());
                last = new IllegalStateException("Provider returned HTTP " + response.getStatusCode().value());
            } catch (HttpStatusCodeException ex) {
                if (ex.getStatusCode().is4xxClientError()) throw new IllegalStateException("Provider rejected request: HTTP " + ex.getStatusCode().value(), ex);
                last = ex;
            } catch (RuntimeException ex) { last = ex; }
            sleep(250L * attempt);
        }
        throw new IllegalStateException("Provider request failed after safe retries", last);
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (!apiKey.isBlank()) h.setBearerAuth(apiKey);
        else if (!tokenUrl.isBlank()) h.setBearerAuth(token());
        return h;
    }

    private String token() {
        if (cachedToken != null && tokenExpiresAt != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(30))) return cachedToken;
        if (clientId.isBlank() || clientSecret.isBlank()) throw new IllegalStateException("OAuth2 provider credentials are incomplete");
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.MultiValueMap<String,String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials"); form.add("client_id", clientId); form.add("client_secret", clientSecret);
        JsonNode n = mapper.convertValue(rest.postForObject(tokenUrl, new HttpEntity<>(form, h), Map.class), JsonNode.class);
        String token = text(n, "access_token", ""); if (token.isBlank()) throw new IllegalStateException("OAuth2 token response did not contain access_token");
        cachedToken = token; tokenExpiresAt = Instant.now().plusSeconds(intValue(n, "expires_in") > 0 ? intValue(n, "expires_in") : 300); return token;
    }

    private JsonNode parse(String body) { try { return mapper.readTree(body == null || body.isBlank() ? "{}" : body); } catch (Exception e) { throw new IllegalStateException("Provider returned invalid JSON", e); } }
    private Map<String,Object> commandMap(BookingCommand c) { Map<String,Object> m=new LinkedHashMap<>(); m.put("shipmentId",c.shipmentId()); m.put("carrierCode",c.carrierCode()); m.put("carrierName",c.carrierName()); m.put("flightNumber",c.flightNumber()); m.put("departureTime",c.departureTime()); m.put("arrivalTime",c.arrivalTime()); m.put("originCode",c.originCode()); m.put("destinationCode",c.destinationCode()); m.put("weightKg",c.weightKg()); m.put("serviceLevel",c.serviceLevel()); return m; }
    private Map<String,Object> amendmentMap(AmendmentCommand c) { Map<String,Object> m=new LinkedHashMap<>(); m.put("flightNumber",c.flightNumber()); m.put("departureTime",c.departureTime()); m.put("arrivalTime",c.arrivalTime()); m.put("weightKg",c.weightKg()); m.put("serviceLevel",c.serviceLevel()); return m; }
    private void requireConfigured(){ if(!configured()) throw new IllegalStateException("Air cargo provider is not configured"); }
    private static String text(JsonNode n,String k){return text(n,k,"");}
    private static String text(JsonNode n,String k,String d){JsonNode v=n==null?null:n.get(k);return v==null||v.isNull()?d:v.asText(d);}
    private static BigDecimal decimal(JsonNode n,String k){String s=text(n,k,null);if(s==null||s.isBlank())return null;try{return new BigDecimal(s);}catch(Exception e){throw new IllegalStateException("Provider returned invalid decimal for "+k);}}
    private static int intValue(JsonNode n,String k){String s=text(n,k,"0");try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private static Instant instant(JsonNode n,String... keys){for(String k:keys){String s=text(n,k,null);if(s!=null&&!s.isBlank())try{return Instant.parse(s);}catch(Exception ignored){}}return null;}
    private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private static String path(String v){if(v==null||v.isBlank())return "";return v.startsWith("/")?v:"/"+v;}
    private static String operationPath(String configured,String reference){String p=path(configured);if(p.contains("{reference}"))return p.replace("{reference}",enc(reference));return p+"/"+enc(reference);}
    private static String strip(String v){return v==null?"":v.trim().replaceAll("/+$","");}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Provider retry interrupted",e);}}
}
