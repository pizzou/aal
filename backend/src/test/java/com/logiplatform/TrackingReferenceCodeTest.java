package com.logiplatform;

import com.logiplatform.service.TrackingReferenceCode;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TrackingReferenceCodeTest {

    @Test
    void codeRoundTripsFullRandomTokenWithoutLosingEntropy() {
        UUID token = UUID.randomUUID();
        String number = TrackingReferenceCode.fromToken(token);
        assertTrue(number.matches("AAL-TRK-[0-9A-F]{32}"));
        assertEquals(token, TrackingReferenceCode.toToken(number));
        assertEquals(token, TrackingReferenceCode.toToken(number.toLowerCase()));
        assertEquals(token, TrackingReferenceCode.toToken(token.toString()));
        assertEquals(token, TrackingReferenceCode.toToken(token.toString().replace("-", "")));
    }

    @Test
    void shipmentBusinessReferenceCannotBecomePublicTrackingCredential() {
        assertThrows(IllegalArgumentException.class,
                () -> TrackingReferenceCode.toToken("AAL-SHP-2026-000001"));
        assertThrows(IllegalArgumentException.class,
                () -> TrackingReferenceCode.toToken("AAL-TRK-ABC"));
        assertThrows(IllegalArgumentException.class,
                () -> TrackingReferenceCode.toToken(""));
        assertThrows(IllegalArgumentException.class,
                () -> TrackingReferenceCode.toToken(null));
    }
}
