package com.logiplatform.service;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Customer-facing tracking number backed by the existing unguessable UUID token.
 * This is a display/transport representation, not a second database identifier.
 * Rotating the token automatically invalidates the old number and share link.
 */
public final class TrackingReferenceCode {

    private static final String PREFIX = "AAL-TRK-";
    private static final Pattern COMPACT = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private TrackingReferenceCode() {
    }

    public static String fromToken(UUID token) {
        if (token == null) {
            throw new IllegalArgumentException("Tracking token is required");
        }
        return PREFIX + token.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    /** Accepts the new tracking number, legacy UUID share tokens and compact UUIDs. */
    public static UUID toToken(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Tracking number is required");
        }
        String value = reference.trim();
        if (value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            value = value.substring(PREFIX.length());
        }
        if (COMPACT.matcher(value).matches()) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-" + value.substring(16, 20) + "-"
                    + value.substring(20);
        }
        if (!UUID_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid tracking number format");
        }
        return UUID.fromString(value);
    }
}
