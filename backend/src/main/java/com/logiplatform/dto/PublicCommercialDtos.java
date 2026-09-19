package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PublicCommercialDtos {
    private PublicCommercialDtos() {}

    public record PublicQuoteOption(
            String mode,
            String modeLabel,
            BigDecimal baseCharge,
            BigDecimal fuelSurcharge,
            BigDecimal totalCharge,
            String currency,
            String rateType,
            boolean available,
            LocalDate validUntil) {}

    public record PublicQuoteRequestResponse(
            String requestToken,
            String quoteReference,
            Instant createdAt,
            LocalDate validUntil,
            String origin,
            String destination,
            String company,
            String contactName,
            String email,
            String phone,
            String commodity,
            Integer packages,
            BigDecimal volumeCbm,
            List<PublicQuoteOption> options) {}
}
