package com.logiplatform.dto;

import com.logiplatform.model.AccessorialCharge;
import com.logiplatform.model.RateCard;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RatingDtos {
    private RatingDtos() {}

    public record SetRateCardRequest(
            @NotBlank String transportMode,
            @NotNull @PositiveOrZero BigDecimal baseRatePerKg,
            @NotNull @PositiveOrZero BigDecimal minCharge,
            @NotNull @PositiveOrZero @DecimalMax("100") BigDecimal fuelSurchargePercent,
            String currency,
            @PositiveOrZero @DecimalMax("100") BigDecimal securitySurchargePercent,
            @PositiveOrZero @DecimalMax("100") BigDecimal markupPercent,
            String laneCode,
            UUID clientId,
            UUID carrierId,
            LocalDate validFrom,
            LocalDate validUntil
    ) {
        public SetRateCardRequest(
                String transportMode,
                BigDecimal baseRatePerKg,
                BigDecimal minCharge,
                BigDecimal fuelSurchargePercent,
                String currency) {
            this(transportMode, baseRatePerKg, minCharge, fuelSurchargePercent, currency,
                    BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null);
        }
    }

    public record RateCardResponse(
            String transportMode,
            BigDecimal baseRatePerKg,
            BigDecimal minCharge,
            BigDecimal fuelSurchargePercent,
            String currency,
            BigDecimal securitySurchargePercent,
            BigDecimal markupPercent,
            String laneCode,
            UUID clientId,
            UUID carrierId,
            LocalDate validFrom,
            LocalDate validUntil,
            boolean active
    ) {
        public static RateCardResponse from(RateCard r) {
            return new RateCardResponse(
                    r.getTransportMode(), r.getBaseRatePerKg(), r.getMinCharge(),
                    r.getFuelSurchargePercent(), r.getCurrency(), r.getSecuritySurchargePercent(),
                    r.getMarkupPercent(), r.getLaneCode(), r.getClientId(), r.getCarrierId(),
                    r.getValidFrom(), r.getValidUntil(), r.isActive());
        }
    }

    public record SetAccessorialRequest(
            @NotBlank String code,
            @NotBlank String description,
            @NotNull @PositiveOrZero BigDecimal amount,
            String currency) {}

    public record AccessorialResponse(
            String code,
            String description,
            BigDecimal amount,
            String currency) {
        public static AccessorialResponse from(AccessorialCharge x) {
            return new AccessorialResponse(x.getCode(), x.getDescription(), x.getAmount(), x.getCurrency());
        }
    }

    public record QuoteRequest(
            @NotBlank String transportMode,
            @NotNull @PositiveOrZero BigDecimal weightKg,
            List<String> accessorialCodes) {}

    public record QuoteResponse(
            BigDecimal rawWeightCharge,
            BigDecimal baseCharge,
            BigDecimal fuelSurcharge,
            BigDecimal accessorialTotal,
            List<AccessorialResponse> accessorials,
            BigDecimal totalCharge,
            String currency,
            String pricingSource) {}
}
