package com.logiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CustomsAuthorityCallbackServiceTest {
    private static final String SECRET = "test-only-customs-shared-secret-minimum-32-chars";
    private final CustomsAuthorityCallbackService service = new CustomsAuthorityCallbackService(
            mock(JdbcTemplate.class), UUID.randomUUID(), SECRET, new ObjectMapper());

    @Test
    void validProviderSignatureCanBeVerified() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String eventId = "verified-release-0001";
        String body = "{\"eventType\":\"RELEASED\"}";
        String digest = sign(timestamp + "." + eventId + "." + body);
        assertDoesNotThrow(() -> service.verify(body, timestamp, eventId, "sha256=" + digest));
    }

    @Test
    void tamperingWithPayloadAfterSigningIsRejected() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String eventId = "provider-event-1001";
        String body = "{\"eventType\":\"SUBMISSION_ACK\"}";
        String digest = sign(timestamp + "." + eventId + "." + body);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.verify(body.replace("SUBMISSION_ACK", "RELEASED"), timestamp, eventId, digest));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void staleOrMissingSignatureIsRejected() {
        String body = "{\"eventType\":\"RELEASED\"}";
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.verify(body, String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond()),
                        "provider-event-1002", "x")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.verify(body, String.valueOf(Instant.now().getEpochSecond()),
                        "provider-event-1002", null)).getStatusCode());
    }

    @Test
    void missingConfiguredSecretFailsClosed() {
        var disabled = new CustomsAuthorityCallbackService(mock(JdbcTemplate.class),
                UUID.randomUUID(), "", new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> disabled.verify("{}", "1", "provider-event-1003", "x")).getStatusCode());
    }

    private static String sign(String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder out = new StringBuilder();
        for (byte b : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }
}
