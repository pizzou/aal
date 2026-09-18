package com.logiplatform.dto;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class CommandCenterShipmentDtos {

    private CommandCenterShipmentDtos() {
    }

    /**
     * Complete operational update contract based on:
     *
     * AAL MOTHERSHIP
     * +
     * AAL Command Center
     *
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