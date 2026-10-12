package com.logiplatform.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class CommandCenterShipmentDtos {

    private CommandCenterShipmentDtos() {
    }

    /** Dedicated partial pricing update used by the guided shipment workflow. */
    public record UpdatePricingRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Client revenue must be greater than zero")
            BigDecimal clientRevenue,
            @NotNull @PositiveOrZero BigDecimal supplierCost,
            @NotNull @PositiveOrZero BigDecimal otherCost,
            @NotNull @PositiveOrZero BigDecimal otherExpenses,
            @NotBlank @Pattern(regexp = "(?i)[A-Z]{3}", message = "Currency must be a 3-letter ISO code")
            String currency
    ) {}

    /**
     * Complete operational update contract based on the AAL workbooks.
     * Calculated fields are deliberately excluded from the request.
     */
    public record UpdateRequest(

            String clientName,

            String contact,

            String commodity,

            String originCountry,

            String originCityPort,

            String destinationCountry,

            String destinationCityPort,

            @PositiveOrZero
            BigDecimal grossWeightKg,

            @PositiveOrZero
            BigDecimal volumetricWeightKg,

            @PositiveOrZero
            Integer packages,

            String airlineUsed,

            String serviceType,

            String operatorName,

            @PositiveOrZero
            BigDecimal supplierCost,

            @PositiveOrZero
            BigDecimal otherCost,

            @PositiveOrZero
            BigDecimal clientRevenue,

            @PositiveOrZero
            BigDecimal amountPaidByClient,

            @PositiveOrZero
            BigDecimal amountPaidToSupply,

            @PositiveOrZero
            BigDecimal otherExpenses,

            String paymentStatus,

            String ownerName,

            String invoiceNo,

            Instant etd,

            Instant eta,

            String nextAction,

            LocalDate nextActionDate,

            String notes,

            String currency,

            /*
             * AAL workbook additions
             */
            LocalDate dateOpened,

            String shipmentStatus
    ) {
    }
}