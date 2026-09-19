package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class PublicQuoteDtos {

    private PublicQuoteDtos() {
        // Utility class
    }

    public record QuoteView(
            UUID shareId,
            String quoteReference,
            LocalDate quoteDate,
            String client,
            String route,
            String serviceType,
            String commodity,
            BigDecimal chargeableWeightKg,
            BigDecimal quotedAmount,
            LocalDate validUntil,
            String status,
            boolean actionable,
            String response) {
    }

    public record QuoteResponseAction(
            String status,
            String message) {
    }
}