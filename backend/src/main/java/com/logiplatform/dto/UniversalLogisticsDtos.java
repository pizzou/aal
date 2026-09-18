package com.logiplatform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

public final class UniversalLogisticsDtos {
    private UniversalLogisticsDtos() {}

    public record CargoRequest(
            @NotNull @Min(1) Integer lineNo,
            @NotBlank String description,
            String packageType,
            @NotNull @Min(1) Integer quantity,
            @PositiveOrZero BigDecimal grossWeightKg,
            @PositiveOrZero BigDecimal volumeM3,
            @Positive BigDecimal lengthCm,
            @Positive BigDecimal widthCm,
            @Positive BigDecimal heightCm,
            String hsCode,
            String countryOfOrigin,
            @PositiveOrZero BigDecimal declaredValue,
            String currency,
            boolean dangerousGoods,
            String dgClass,
            String unNumber,
            boolean temperatureControlled,
            BigDecimal minTemperatureC,
            BigDecimal maxTemperatureC,
            boolean stackable,
            boolean fragile) {}

    public record LegRequest(
            @NotNull @Min(1) Integer sequenceNo,
            @NotBlank String mode,
            String carrierName,
            String carrierReference,
            String originCode,
            String originName,
            String destinationCode,
            String destinationName,
            Instant plannedDeparture,
            Instant plannedArrival,
            String equipmentType,
            String notes) {}
}
