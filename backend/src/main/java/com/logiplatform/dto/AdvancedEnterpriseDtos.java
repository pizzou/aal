package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class AdvancedEnterpriseDtos {
    private AdvancedEnterpriseDtos() {}

    public record DangerousGoodsRequest(
            @NotNull UUID shipmentId,
            UUID cargoItemId,
            @NotBlank @Pattern(regexp = "UN[0-9]{4}") String unNumber,
            @NotBlank String properShippingName,
            @NotBlank String hazardClass,
            String packingGroup,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank String quantityUnit,
            @NotNull @Positive Integer packageCount,
            String tunnelCode,
            boolean marinePollutant,
            boolean limitedQuantity,
            boolean exceptedQuantity) {}

    public record PricingDiscountRequest(
            @NotBlank String discountCode,
            UUID clientId,
            String mode,
            String laneCode,
            @PositiveOrZero BigDecimal minCharge,
            @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal discountPercent,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            @PositiveOrZero Integer priority) {}

    public record MarginControlRequest(
            UUID clientId,
            String mode,
            @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal minimumMarginPercent,
            boolean hardBlock,
            @NotNull LocalDate validFrom,
            LocalDate validUntil) {}

    public record SmsRequest(
            UUID shipmentId,
            @NotBlank String recipient,
            @NotBlank String message,
            @NotBlank String idempotencyKey) {}

    public record JourneyCreateRequest(
            @NotNull UUID shipmentId,
            @NotBlank String journeyReference,
            String serviceType,
            String status) {}

    public record JourneyLegRequest(
            @NotNull UUID shipmentId,
            @NotNull @Positive Integer sequenceNo,
            @NotBlank String mode,
            @NotBlank String originCode,
            @NotBlank String destinationCode,
            String originName,
            String destinationName,
            String carrierName,
            String carrierReference,
            String plannedDeparture,
            String plannedArrival) {}
}
