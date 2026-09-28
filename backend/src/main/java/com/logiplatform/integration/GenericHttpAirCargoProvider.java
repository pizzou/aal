package com.logiplatform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import com.logiplatform.integration.control.CircuitBreakerService;
import com.logiplatform.integration.control.ExternalOperationException;
import com.logiplatform.integration.control.OperationRetryPolicy;
import com.logiplatform.integration.control.ProviderRateLimiter;
import com.logiplatform.integration.control.IntegrationMetricsService;
import com.logiplatform.integration.security.IntegrationCredentialService;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
    private final CircuitBreakerService circuitBreaker;
    private final ProviderErrorMapper errorMapper; private final ProviderResponseValidator responseValidator; private final ProviderRateLimiter rateLimiter; private final IntegrationMetricsService metrics; private final IntegrationCredentialService credentialService; private final String accountCode;
    private final boolean signingEnabled; private final String signingSecret; private final String signingHeader;
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
            @Value("${aircargo.provider.standards:GENERIC_JSON}") String standards,
            CircuitBreakerService circuitBreaker, ProviderErrorMapper errorMapper, ProviderResponseValidator responseValidator, ProviderRateLimiter rateLimiter, IntegrationMetricsService metrics, IntegrationCredentialService credentialService,
            @Value("${aircargo.provider.request-signing.enabled:false}") boolean signingEnabled,
            @Value("${aircargo.provider.request-signing.secret:}") String signingSecret,
            @Value("${aircargo.provider.request-signing.header:X-AAL-Signature}") String signingHeader,
            @Value("${aircargo.provider.account-code:}") String accountCode) {
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
        this.circuitBreaker = circuitBreaker; this.errorMapper=errorMapper; this.responseValidator=responseValidator; this.rateLimiter=rateLimiter; this.metrics=metrics; this.credentialService=credentialService; this.accountCode=accountCode==null?"":accountCode.trim().toUpperCase(); this.signingEnabled=signingEnabled; this.signingSecret=signingSecret==null?"":signingSecret.trim(); this.signingHeader=signingHeader==null||signingHeader.isBlank()?"X-AAL-Signature":signingHeader.trim();
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
        responseValidator.capacity(n);
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

    @Override
    public AwbSubmissionResult getAwb(String providerReference) {
        requireConfigured();
        JsonNode n = getJson(baseUrl + operationPath(awbPath, providerReference));
        return new AwbSubmissionResult(text(n,"status","UNKNOWN"), text(n,"providerReference",providerReference), n.toString());
    }

    private BookingResult booking(HttpMethod method, String path, String key, Map<String,Object> payload, boolean cancellation) {
        requireConfigured();
        JsonNode n = request(method, path, payload, key);
        responseValidator.booking(n);
        String status = text(n, "status", cancellation ? "CANCELLED" : "PENDING").toUpperCase(Locale.ROOT);
        String ref = text(n, "providerReference", text(n, "reference", null));
        String confirmation = text(n, "confirmationNumber", text(n, "confirmation", ref));
        BigDecimal weight = decimal(n, "confirmedWeightKg");
        return new BookingResult(status, ref, confirmation, weight, n.toString());
    }

    private JsonNode getJson(String url) { return request(HttpMethod.GET, url, null, UUID.randomUUID().toString()); }

    private JsonNode request(HttpMethod method, String pathOrUrl, Object payload, String idempotencyKey) {
        requireConfigured();
        OperationRetryPolicy policy = OperationRetryPolicy.classify(method, idempotencyKey);
        if (!circuitAllowed()) throw new ExternalOperationException("Provider circuit breaker is OPEN", null, true, null);
        int maxAttempts = policy == OperationRetryPolicy.NEVER_RETRY ? 1 : 3;
        long totalStarted=System.nanoTime();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long started=System.nanoTime();
            try {
                rateLimiter.acquire(providerCode);
                String url = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + path(pathOrUrl);
                HttpHeaders h = headers();
                if (idempotencyKey != null && !idempotencyKey.isBlank()) h.set("Idempotency-Key", idempotencyKey);
                String rawBody=payload==null?"":mapper.valueToTree(payload).toString();
                String effectiveSigningSecret=credential("SIGNING_SECRET"); if(effectiveSigningSecret.isBlank()) effectiveSigningSecret=signingSecret; if(signingEnabled && !effectiveSigningSecret.isBlank()){ String ts=Long.toString(Instant.now().getEpochSecond()); h.set("X-AAL-Timestamp",ts); h.set(signingHeader,sign(ts,method.name(),pathOrUrl,rawBody,effectiveSigningSecret)); }
                ResponseEntity<String> response = rest.exchange(url, method, new HttpEntity<>(payload, h), String.class);
                if (response.getStatusCode().is2xxSuccessful()) { long latency=elapsed(started); circuitSuccess(latency,response.getStatusCode().value()); metrics.providerSuccess(providerCode,latency); return parse(response.getBody()); }
                if (response.getStatusCode().is4xxClientError()) {
                    if(response.getStatusCode().value()==429 && policy!=OperationRetryPolicy.NEVER_RETRY){ last=new ExternalOperationException("PROVIDER_RATE_LIMITED",null,false,429); sleep(1000L*attempt); continue; }
                    circuitSuccess(elapsed(started),response.getStatusCode().value()); throw new ExternalOperationException(errorMapper.map(response.getStatusCode().value(),response.getBody()), null, false, response.getStatusCode().value());
                }
                last = new ExternalOperationException(errorMapper.map(response.getStatusCode().value(),response.getBody()), null, method != HttpMethod.GET, response.getStatusCode().value());
            } catch (HttpStatusCodeException ex) {
                if (ex.getStatusCode().is4xxClientError()) {
                    if(ex.getStatusCode().value()==429 && policy!=OperationRetryPolicy.NEVER_RETRY){ last=new ExternalOperationException("PROVIDER_RATE_LIMITED",ex,false,429); sleep(1000L*attempt); continue; }
                    throw new ExternalOperationException(errorMapper.map(ex.getStatusCode().value(),ex.getResponseBodyAsString()), ex, false, ex.getStatusCode().value());
                }
                last = new ExternalOperationException(errorMapper.map(ex.getStatusCode().value(),ex.getResponseBodyAsString()), ex, method != HttpMethod.GET, ex.getStatusCode().value());
            } catch (org.springframework.web.client.ResourceAccessException ex) {
                last = new ExternalOperationException("Provider transport failure", ex, method != HttpMethod.GET, null);
            } catch (RuntimeException ex) {
                last = new ExternalOperationException("Provider request failed", ex, method != HttpMethod.GET, null);
            }
            if (attempt < maxAttempts) sleep(250L * attempt);
        }
        long latency=elapsed(totalStarted); Integer finalStatus=last instanceof ExternalOperationException e?e.httpStatus():null; if(finalStatus==null||finalStatus!=429){ circuitFailure(latency,finalStatus); metrics.providerFailure(providerCode,latency,last==null?"UNKNOWN":last.getMessage()); }
        if (last instanceof ExternalOperationException e) throw e;
        throw new ExternalOperationException("Provider request failed", last, method != HttpMethod.GET, null);
    }

    private boolean circuitAllowed(){ try{return circuitBreaker.allow(providerCode);}catch(Exception ignored){return true;} }
    private void circuitSuccess(long latency,int status){ try{circuitBreaker.success(providerCode,latency,status);}catch(Exception ignored){} }
    private void circuitFailure(long latency,Integer status){ try{circuitBreaker.failure(providerCode,latency,status);}catch(Exception ignored){}}
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1_000_000L);}

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.setAccept(List.of(MediaType.APPLICATION_JSON));
        String dbApiKey=credential("API_KEY");
        if (!dbApiKey.isBlank() || !apiKey.isBlank()) h.setBearerAuth(dbApiKey.isBlank()?apiKey:dbApiKey);
        else if (!tokenUrl.isBlank()) h.setBearerAuth(token());
        return h;
    }

    private String token() {
        if (cachedToken != null && tokenExpiresAt != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(30))) return cachedToken;
        String dbClientId=credential("OAUTH2_CLIENT_ID"); String dbClientSecret=credential("OAUTH2_CLIENT_SECRET");
        String effectiveClientId=dbClientId.isBlank()?clientId:dbClientId; String effectiveClientSecret=dbClientSecret.isBlank()?clientSecret:dbClientSecret;
        if (effectiveClientId.isBlank() || effectiveClientSecret.isBlank()) throw new IllegalStateException("OAuth2 provider credentials are incomplete");
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.MultiValueMap<String,String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials"); form.add("client_id", effectiveClientId); form.add("client_secret", effectiveClientSecret);
        JsonNode n = mapper.convertValue(rest.postForObject(tokenUrl, new HttpEntity<>(form, h), Map.class), JsonNode.class);
        String token = text(n, "access_token", ""); if (token.isBlank()) throw new IllegalStateException("OAuth2 token response did not contain access_token");
        cachedToken = token; tokenExpiresAt = Instant.now().plusSeconds(intValue(n, "expires_in") > 0 ? intValue(n, "expires_in") : 300); return token;
    }

    private static String sign(String timestamp,String method,String path,String body,String secret){ try{String bodyHash=sha256(body);Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[] d=mac.doFinal((timestamp+"\n"+method+"\n"+path+"\n"+bodyHash).getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException("Unable to sign provider request",e);} }
    private static String sha256(String value){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}

    private String credential(String type){ if(accountCode.isBlank()) return ""; try{ return credentialService.resolveActiveSecretByCode(accountCode,type).orElse(""); }catch(Exception ignored){ return ""; } }

    private JsonNode parse(String body) { try { return mapper.readTree(body == null || body.isBlank() ? "{}" : body); } catch (Exception e) { throw new IllegalStateException("Provider returned invalid JSON", e); } }
    private Map<String,Object> commandMap(BookingCommand c) {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("shipmentId",c.shipmentId());
        m.put("carrierCode",c.carrierCode());
        m.put("carrierName",c.carrierName());
        m.put("flightNumber",c.flightNumber());
        m.put("departureTime",c.departureTime());
        m.put("arrivalTime",c.arrivalTime());
        m.put("originCode",c.originCode());
        m.put("destinationCode",c.destinationCode());
        m.put("weightKg",c.weightKg());
        m.put("serviceLevel",c.serviceLevel());
        if(c.providerReference()!=null&&!c.providerReference().isBlank())m.put("providerReference",c.providerReference());
        if(c.offerReference()!=null&&!c.offerReference().isBlank())m.put("offerReference",c.offerReference());
        if(c.rateReference()!=null&&!c.rateReference().isBlank())m.put("rateReference",c.rateReference());
        return m;
    }
    private Map<String,Object> amendmentMap(AmendmentCommand c) { Map<String,Object> m=new LinkedHashMap<>(); m.put("flightNumber",c.flightNumber()); m.put("departureTime",c.departureTime()); m.put("arrivalTime",c.arrivalTime()); m.put("weightKg",c.weightKg()); m.put("serviceLevel",c.serviceLevel()); if(c.providerReference()!=null&&!c.providerReference().isBlank())m.put("providerReference",c.providerReference()); return m; }
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
