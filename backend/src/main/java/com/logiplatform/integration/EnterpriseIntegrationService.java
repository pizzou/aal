package com.logiplatform.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class EnterpriseIntegrationService {

    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final JdbcTemplate db;
    private final String dcsaBaseUrl;
    private final String customsBaseUrl;
    private final String portBaseUrl;
    private final String accountingBaseUrl;
    private final String whatsappBaseUrl;
    private final String whatsappToken;
    private final String ediEnabled;
    private final String ediBaseUrl;
    private final String webhookSecret;
    private final Set<String> webhookAllowedHosts;

    public EnterpriseIntegrationService(
            RestTemplate rest,
            ObjectMapper mapper,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            @Value("${integration.dcsa.base-url:}") String dcsaBaseUrl,
            @Value("${aircargo.customs.base-url:}") String customsBaseUrl,
            @Value("${integration.port.base-url:}") String portBaseUrl,
            @Value("${integration.accounting.base-url:}") String accountingBaseUrl,
            @Value("${integration.whatsapp.base-url:https://graph.facebook.com}") String whatsappBaseUrl,
            @Value("${integration.whatsapp.token:}") String whatsappToken,
            @Value("${integration.edi.enabled:true}") String ediEnabled,
            @Value("${integration.edi.base-url:}") String ediBaseUrl,
            @Value("${integration.webhook.secret:}") String webhookSecret,
            @Value("${integration.webhook.allowed-hosts:}") String webhookAllowedHosts) {
        this.rest = rest;
        this.mapper = mapper;
        this.db = db;
        this.dcsaBaseUrl = strip(dcsaBaseUrl);
        this.customsBaseUrl = strip(customsBaseUrl);
        this.portBaseUrl = strip(portBaseUrl);
        this.accountingBaseUrl = strip(accountingBaseUrl);
        this.whatsappBaseUrl = strip(whatsappBaseUrl);
        this.whatsappToken = whatsappToken == null ? "" : whatsappToken.trim();
        this.ediEnabled = ediEnabled == null ? "true" : ediEnabled.trim();
        this.ediBaseUrl = strip(ediBaseUrl);
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.webhookAllowedHosts = parseHosts(webhookAllowedHosts);
    }

    public Map<String, Object> health() {
        return Map.of(
                "DCSA", configured(dcsaBaseUrl),
                "CUSTOMS", configured(customsBaseUrl),
                "PORT", configured(portBaseUrl),
                "ACCOUNTING", configured(accountingBaseUrl),
                "WHATSAPP", configured(whatsappToken),
                "EDI", "true".equalsIgnoreCase(ediEnabled) && (ediBaseUrl.isBlank() || configured(ediBaseUrl)));
    }

    public Map<String, Object> dcsaTrack(String carrierCode, String equipmentReference) {
        return getJson(dcsaBaseUrl, "/v1/track/" + enc(carrierCode) + "/" + enc(equipmentReference),
                "DCSA", "TRACK", stableKey("DCSA", carrierCode, equipmentReference));
    }

    public Map<String, Object> portEvents(String portCode, String equipmentReference) {
        return getJson(portBaseUrl, "/v1/events/" + enc(portCode) + "/" + enc(equipmentReference),
                "PORT", "EVENTS", stableKey("PORT", portCode, equipmentReference));
    }

    public Map<String, Object> customsSubmit(Map<String, Object> payload) {
        String endpoint = "/v1/customs/submissions";
        return postJson(customsBaseUrl, endpoint, payload,
                "CUSTOMS", "SUBMIT", idempotency(payload));
    }

    public Map<String, Object> accountingPost(Map<String, Object> payload) {
        return postJson(accountingBaseUrl, "/api/method/accounting/integration", payload,
                "ACCOUNTING", "POST", idempotency(payload));
    }

    public Map<String, Object> sendWhatsApp(String phoneNumber, String message) {
        if (whatsappToken.isBlank()) {
            throw new IllegalStateException("WhatsApp Cloud API token is not configured");
        }
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", phoneNumber,
                "type", "text",
                "text", Map.of("body", message));
        HttpHeaders headers = headers();
        headers.setBearerAuth(whatsappToken);
        return postJsonWithHeaders(whatsappBaseUrl + "/v20.0/messages", payload,
                "WHATSAPP", "SEND_MESSAGE", idempotency(payload), headers);
    }


    public Map<String, Object> sendEdi(String payload, String messageType, String idempotencyKey) {
        if (!"true".equalsIgnoreCase(ediEnabled)) {
            throw new IllegalStateException("EDI integration is disabled");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("EDI payload must not be blank");
        }
        if (ediBaseUrl.isBlank()) {
            throw new IllegalStateException("EDI endpoint is not configured");
        }
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("messageType", cleanUpper(messageType, "IFTMIN"));
        body.put("payload", payload);
        body.put("idempotencyKey", idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey);
        return postJson(ediBaseUrl, "", body, "EDI", "SEND", String.valueOf(body.get("idempotencyKey")));
    }

    public Map<String, Object> deliverWebhook(String eventType, String targetUrl,
                                               Map<String,Object> payload, String idempotencyKey) {
        validateWebhookTarget(targetUrl);
        String body = write(payload == null ? Map.of() : payload);
        HttpHeaders headers = headers();
        headers.set("X-AAL-Event", eventType == null ? "EVENT" : eventType);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? UUID.randomUUID().toString() : idempotencyKey;
        headers.set("Idempotency-Key", key);
        headers.set("X-AAL-Signature", hmac(webhookSecret, body));

        UUID tenant = TenantContext.getTenantId();
        UUID attempt = UUID.randomUUID();
        db.update("""
                INSERT INTO webhook_delivery_attempts(
                    id,tenant_id,event_type,target_url,idempotency_key,attempt_no,status)
                VALUES(?,?,?,?,?,1,'STARTED')
                """, attempt, tenant, eventType == null ? "EVENT" : eventType, targetUrl, key);
        try {
            ResponseEntity<Map<String,Object>> response = rest.exchange(
                    targetUrl, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String,Object>>() {});
            String status = response.getStatusCode().is2xxSuccessful() ? "DELIVERED" : "FAILED";
            db.update("""
                    UPDATE webhook_delivery_attempts
                       SET status=?,http_status=?,response_excerpt=?,completed_at=now()
                     WHERE id=? AND tenant_id=?
                    """, status, response.getStatusCode().value(), excerpt(response.getBody()), attempt, tenant);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Webhook endpoint returned HTTP " + response.getStatusCode().value());
            }
            return Map.of("delivered", true, "status", response.getStatusCode().value(), "idempotencyKey", key);
        } catch (RuntimeException ex) {
            db.update("""
                    UPDATE webhook_delivery_attempts
                       SET status='FAILED',error_detail=?,completed_at=now()
                     WHERE id=? AND tenant_id=?
                    """, ex.getMessage(), attempt, tenant);
            throw ex;
        }
    }

    public Map<String, Object> buildEdi(String standard, String messageType,
                                         String controlReference, List<List<String>> segments) {
        if (!"true".equalsIgnoreCase(ediEnabled)) {
            throw new IllegalStateException("EDI integration is disabled");
        }
        String normalizedStandard = cleanUpper(standard, "EDIFACT");
        String normalizedType = cleanUpper(messageType, "IFTMIN");
        String control = controlReference == null || controlReference.isBlank()
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 14)
                : controlReference.trim();

        List<List<String>> safeSegments = segments == null ? List.of() : segments;
        int segmentCount = safeSegments.size() + 2;
        StringBuilder payload = new StringBuilder();
        payload.append("UNB+UNOA:1+").append(control).append("'")
                .append("UNH+").append(control).append("+").append(normalizedType).append("'");
        for (List<String> segment : safeSegments) {
            payload.append(String.join("+", segment == null ? List.of() : segment)).append("'");
        }
        payload.append("UNT+").append(segmentCount).append("+").append(control).append("'");

        UUID id = UUID.randomUUID();
        String hash = hash(payload.toString());
        db.update("""
                INSERT INTO edi_messages(
                    id,tenant_id,standard,message_type,control_reference,direction,status,payload,payload_hash)
                VALUES(?,?,?, ?,?,'OUTBOUND','READY',?,?)
                """, id, TenantContext.getTenantId(), normalizedStandard, normalizedType,
                control, payload.toString(), hash);

        return Map.of("id", id, "standard", normalizedStandard, "messageType", normalizedType,
                "controlReference", control, "payload", payload.toString(), "payloadHash", hash);
    }

    public Map<String, Object> parseEdi(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("EDI payload must not be blank");
        }
        String[] segments = payload.split("'", -1);
        List<List<String>> parsed = new ArrayList<>();
        String type = "UNKNOWN";
        String control = "";
        for (String raw : segments) {
            if (raw.isBlank()) continue;
            String[] fields = raw.split("\\+", -1);
            parsed.add(Arrays.asList(fields));
            if (fields.length > 1 && "UNH".equals(fields[0])) {
                control = fields[1];
                if (fields.length > 2) type = fields[2];
            }
        }

        UUID id = UUID.randomUUID();
        String hash = hash(payload);
        db.update("""
                INSERT INTO edi_messages(
                    id,tenant_id,standard,message_type,control_reference,direction,status,payload,payload_hash,parsed_json,processed_at)
                VALUES(?,?,?,?,?,'INBOUND','PARSED',?,?,?,now())
                """, id, TenantContext.getTenantId(), "EDIFACT", type, control, payload, hash, write(parsed));

        return Map.of("standard", "EDIFACT", "messageType", type,
                "controlReference", control, "segments", parsed, "payloadHash", hash);
    }

    private Map<String, Object> getJson(String base, String path, String provider,
                                         String operation, String idempotencyKey) {
        if (base.isBlank()) throw new IllegalStateException(provider + " integration is not configured");
        UUID attempt = begin(provider, operation, idempotencyKey, "OUTBOUND", null);
        try {
            ResponseEntity<Map<String, Object>> response = rest.exchange(
                    base + path,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            complete(attempt, "SUCCESS", response.getStatusCode().value(), null, response.getBody());
            return response.getBody() == null ? Map.of() : response.getBody();
        } catch (RestClientException ex) {
            complete(attempt, "FAILED", null, ex.getMessage(), null);
            throw ex;
        }
    }

    private Map<String, Object> postJson(String base, String path, Map<String, Object> payload,
                                          String provider, String operation, String idempotencyKey) {
        return postJsonWithHeaders(base + path, payload, provider, operation, idempotencyKey, headers());
    }

    private Map<String, Object> postJsonWithHeaders(String url, Map<String, Object> payload,
                                                     String provider, String operation, String idempotencyKey,
                                                     HttpHeaders headers) {
        if (url == null || url.isBlank()) throw new IllegalStateException(provider + " integration is not configured");
        UUID attempt = begin(provider, operation, idempotencyKey, "OUTBOUND", payload);
        try {
            ResponseEntity<Map<String, Object>> response = rest.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(payload, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            complete(attempt, response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED",
                    response.getStatusCode().value(), null, response.getBody());
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(provider + " returned HTTP " + response.getStatusCode().value());
            }
            return response.getBody() == null ? Map.of() : response.getBody();
        } catch (RuntimeException ex) {
            complete(attempt, "FAILED", null, ex.getMessage(), null);
            throw ex;
        }
    }

    private UUID begin(String provider, String operation, String idempotencyKey,
                       String direction, Object request) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO integration_attempts(
                    id,tenant_id,provider,operation,direction,idempotency_key,
                    correlation_id,status,request_hash)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, id, TenantContext.getTenantId(), provider, operation, direction,
                idempotencyKey, UUID.randomUUID().toString(), "STARTED",
                request == null ? null : hash(write(request)));
        return id;
    }

    private void complete(UUID attempt, String status, Integer httpStatus,
                          String error, Object response) {
        db.update("""
                UPDATE integration_attempts
                   SET status=?, http_status=?, error_detail=?, response_hash=?, completed_at=now()
                 WHERE id=? AND tenant_id=?
                """, status, httpStatus, error,
                response == null ? null : hash(write(response)), attempt, TenantContext.getTenantId());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private static String idempotency(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("idempotencyKey");
        return value == null || String.valueOf(value).isBlank()
                ? UUID.randomUUID().toString()
                : String.valueOf(value);
    }

    private static String stableKey(String provider, String... values) {
        return provider + ":" + hash(String.join("|", values == null ? new String[0] : values));
    }

    private static String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void validateWebhookTarget(String targetUrl) {
        try {
            java.net.URI uri = java.net.URI.create(targetUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Webhook target must use HTTPS and include a hostname");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.startsWith("10.") ||
                    host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) {
                throw new IllegalArgumentException("Webhook target resolves to a private/local address");
            }
            if (!webhookAllowedHosts.isEmpty() && webhookAllowedHosts.stream().noneMatch(allowed ->
                    host.equals(allowed) || host.endsWith("." + allowed))) {
                throw new IllegalArgumentException("Webhook target host is not allow-listed");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid webhook target URL", ex);
        }
    }

    private static Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String item : value.split(",")) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.isBlank()) result.add(host);
        }
        return Set.copyOf(result);
    }

    private static String excerpt(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static String hmac(String secret, String body) {
        if (secret == null || secret.isBlank()) return "";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign webhook payload", ex);
        }
    }

    private static boolean configured(String value) { return value != null && !value.isBlank(); }
    private static String firstNonBlank(String a, String b) { return configured(a) ? a : b; }
    private static String strip(String value) { return value == null ? "" : value.trim().replaceAll("/+$", ""); }
    private static String enc(String value) { return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private static String cleanUpper(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT); }
}
