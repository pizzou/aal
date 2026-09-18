package com.logiplatform.dto;

import com.logiplatform.model.AccessorialCharge;
import com.logiplatform.model.RateCard;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

public final class RatingDtos {

    private RatingDtos() {}

    public record SetRateCardRequest(
            @NotBlank String transportMode,
            @NotNull @PositiveOrZero BigDecimal baseRatePerKg,
            @NotNull @PositiveOrZero BigDecimal minCharge,
            @NotNull @PositiveOrZero BigDecimal fuelSurchargePercent,
            String currency
    ) {}

    public record RateCardResponse(
            String transportMode, BigDecimal baseRatePerKg, BigDecimal minCharge,
            BigDecimal fuelSurchargePercent, String currency
    ) {
        public static RateCardResponse from(RateCard r) {
            return new RateCardResponse(r.getTransportMode(), r.getBaseRatePerKg(),
                    r.getMinCharge(), r.getFuelSurchargePercent(), r.getCurrency());
        }
    }

    public record SetAccessorialRequest(
            @NotBlank String code,
            @NotBlank String description,
            @NotNull @PositiveOrZero BigDecimal amount,
            String currency
    ) {}

    public record AccessorialResponse(String code, String description, BigDecimal amount, String currency) {
        public static AccessorialResponse from(AccessorialCharge a) {
            return new AccessorialResponse(a.getCode(), a.getDescription(), a.getAmount(), a.getCurrency());
        }
    }

    public record QuoteRequest(
            @NotBlank String transportMode,
            @NotNull @PositiveOrZero BigDecimal weightKg,
            List<String> accessorialCodes
    ) {}

    public record QuoteResponse(
            BigDecimal rawWeightCharge,
            BigDecimal baseCharge,
            BigDecimal fuelSurcharge,
            BigDecimal accessorialTotal,
            List<AccessorialResponse> appliedAccessorials,
            BigDecimal totalCharge,
            String currency,
            String rateType
    ) {}
}
