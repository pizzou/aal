package com.logiplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An HMAC-authenticated customs/broker callback. Possession of staff credentials
 * does not confer the authority to set a declaration to RELEASED.
 *
 * The signing key must be provisioned out of band to an independently verified
 * customs or broker integration; a shared key by itself is not government certification.
 */
@Service
public class CustomsAuthorityCallbackService {
    private static final Set<String> EVENTS = Set.of("SUBMISSION_ACK", "RELEASED", "HOLD", "REJECTED");
    private final JdbcTemplate db;
    private final UUID tenantId;
    private final String secret;
    private final ObjectMapper json;

    public CustomsAuthorityCallbackService(
            @Qualifier("publicJdbcTemplate") JdbcTemplate db,
            @Value("${app.single-tenant.id}") UUID tenantId,
            @Value("${customs.webhook.secret:}") String secret,
            ObjectMapper json) {
        this.db = db;
        this.tenantId = tenantId;
        this.secret = secret == null ? "" : secret;
        this.json = json;
    }

    /** Exactly the bytes received from the provider must be HMAC signed. */
    public void verify(String body, String timestamp, String eventId, String signature) {
        if (secret.length() < 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Customs webhook integration is not configured");
        }
        if (body == null || body.length() > 64_000 || eventId == null
                || !eventId.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid customs event");
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing customs event timestamp");
        }
        if (Math.abs(Instant.now().getEpochSecond() - sent) > 300) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customs event timestamp outside replay window");
        }
        String supplied = signature == null ? "" : signature.trim().toLowerCase(Locale.ROOT);
        if (supplied.startsWith("sha256=")) supplied = supplied.substring(7);
        if (!supplied.matches("[a-f0-9]{64}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing customs event signature");
        }
        String expected = hmac(secret, timestamp + "." + eventId + "." + body);
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid customs event signature");
        }
    }

    @Transactional(transactionManager = "publicTransactionManager")
    public Map<String, Object> applyVerified(String body, String eventId) {
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed customs acknowledgment JSON");
        }
        if (event == null || !event.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customs acknowledgment must be an object");
        }
        String kind = event.path("eventType").asText("").trim().toUpperCase(Locale.ROOT);
        String reference = event.path("externalReference").asText("").trim();
        String provider = event.path("providerCode").asText("").trim().toUpperCase(Locale.ROOT);
        UUID declarationId;
        try {
            declarationId = UUID.fromString(event.path("declarationId").asText());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid customs declaration ID");
        }
        if (!EVENTS.contains(kind) || !provider.matches("[A-Z0-9_-]{2,64}")
                || reference.isBlank() || reference.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A recognized customs event, provider and authority reference are required");
        }
        Integer alreadyProcessed = db.queryForObject(
                "SELECT count(*) FROM customs_authority_events WHERE tenant_id=? AND event_id=?",
                Integer.class, tenantId, eventId);
        if (alreadyProcessed != null && alreadyProcessed > 0) {
            return Map.of("eventId", eventId, "status", "ALREADY_PROCESSED");
        }
        Map<String, Object> declaration;
        try {
            declaration = db.queryForMap(
                    "SELECT id,status,external_reference FROM customs_declarations WHERE tenant_id=? AND id=? FOR UPDATE",
                    tenantId, declarationId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customs declaration not found");
        }
        String previous = String.valueOf(declaration.get("status")).toUpperCase(Locale.ROOT);
        String existingReference = declaration.get("external_reference") == null
                ? "" : String.valueOf(declaration.get("external_reference"));
        if ("SUBMISSION_ACK".equals(kind)) {
            if (!Set.of("QUEUED", "READY_FOR_SUBMISSION").contains(previous)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot acknowledge a customs declaration that has not been queued");
            }
        } else if (!"SUBMITTED".equals(previous) || !reference.equals(existingReference)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Release, hold or rejection requires a matching prior authority submission acknowledgment");
        }

        int updated = db.update(
                "UPDATE customs_declarations SET status=?, external_reference=?, "
                        + "submitted_at=CASE WHEN ?='SUBMITTED' THEN now() ELSE submitted_at END, "
                        + "submission_message=?, updated_at=now() WHERE tenant_id=? AND id=? AND status=?",
                "SUBMISSION_ACK".equals(kind) ? "SUBMITTED" : kind,
                reference, "SUBMISSION_ACK".equals(kind) ? "SUBMITTED" : kind,
                "Verified provider event " + eventId + " (" + provider + ")",
                tenantId, declarationId, previous);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent customs state transition detected");
        }
        String hash = sha256(body);
        db.update("INSERT INTO customs_authority_events(tenant_id,event_id,declaration_id,provider_code,event_type,external_reference,payload_hash) "
                        + "VALUES(?,?,?,?,?,?,?)",
                tenantId, eventId, declarationId, provider, kind, reference, hash);
        if ("SUBMISSION_ACK".equals(kind)) {
            db.update("UPDATE customs_submission_attempts SET status='ACKNOWLEDGED',external_reference=?,response_message=? "
                            + "WHERE tenant_id=? AND declaration_id=? AND id=(SELECT id FROM customs_submission_attempts "
                            + "WHERE tenant_id=? AND declaration_id=? AND status='QUEUED' ORDER BY attempted_at DESC LIMIT 1)",
                    reference, "Provider receipt confirmed by signed callback", tenantId, declarationId,
                    tenantId, declarationId);
        }
        return Map.of("eventId", eventId, "declarationId", declarationId, "status",
                "SUBMISSION_ACK".equals(kind) ? "SUBMITTED" : kind);
    }

    private static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify customs signature", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash customs event", ex);
        }
    }

    private static String hex(byte[] input) {
        StringBuilder builder = new StringBuilder(input.length * 2);
        for (byte item : input) builder.append(String.format("%02x", item & 0xff));
        return builder.toString();
    }
}
