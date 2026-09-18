package com.logiplatform.dto;

import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentTrackingEvent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ShipmentDtos {

    private ShipmentDtos() {
    }

    public record CreateShipmentRequest(
            @NotBlank String referenceCode,
            @NotBlank String originAddress,
            @NotBlank String destinationAddress,
            @NotNull String transportMode,
            String carrierName,
            String carrierReferenceNumber
    ) {
    }

    public record UpdateStatusRequest(
            @NotBlank String status
    ) {
    }

    public record AddTrackingEventRequest(
            @NotBlank String eventType,
            String location,
            String notes,
            Instant occurredAt
    ) {
    }

    public record TrackingEventResponse(
            UUID id,
            String eventType,
            String location,
            String notes,
            Instant occurredAt
    ) {

        public static TrackingEventResponse from(
                ShipmentTrackingEvent event
        ) {
            return new TrackingEventResponse(
                    event.getId(),
                    event.getEventType().name(),
                    event.getLocation(),
                    event.getNotes(),
                    event.getOccurredAt()
            );
        }
    }

    public record ShipmentResponse(
            UUID id,
            String referenceCode,
            String originAddress,
            String destinationAddress,
            String status,
            String transportMode,
            String carrierName,
            String carrierReferenceNumber,
            UUID trackingToken,
            Integer weightKg,
            String notificationEmail,
            String flightNumber,

            /*
             * AAL workbook field
             */
            LocalDate dateOpened,

            String clientName,
            String contact,
            String commodity,

            String originCountry,
            String originCityPort,

            String destinationCountry,
            String destinationCityPort,

            BigDecimal grossWeightKg,
            BigDecimal volumetricWeightKg,
            BigDecimal chargeableWeightKg,

            Integer packages,

            String airlineUsed,
            String serviceType,
            String operatorName,

            Instant etd,
            Instant eta,

            BigDecimal supplierCost,
            BigDecimal otherCost,
            BigDecimal totalCost,

            BigDecimal amountBilledToClient,
            BigDecimal amountPaidByClient,
            BigDecimal amountRemaining,

            BigDecimal otherExpenses,
            BigDecimal amountPaidToSupply,

            BigDecimal netIncome,
            BigDecimal marginPercent,

            String invoiceNo,
            String paymentStatus,
            String ownerName,

            String nextAction,
            LocalDate nextActionDate,

            String notes,
            String currency,

            Instant createdAt,
            Instant updatedAt
    ) {

        public static ShipmentResponse from(
                Shipment shipment
        ) {

            return new ShipmentResponse(

                    shipment.getId(),

                    shipment.getReferenceCode(),

                    shipment.getOriginAddress(),

                    shipment.getDestinationAddress(),

                    shipment.getStatus().name(),

                    shipment.getTransportMode().name(),

                    shipment.getCarrierName(),

                    shipment.getCarrierReferenceNumber(),

                    shipment.getTrackingToken(),

                    shipment.getWeightKg(),

                    shipment.getNotificationEmail(),

                    shipment.getFlightNumber(),

                    shipment.getDateOpened(),

                    shipment.getClientName(),

                    shipment.getContact(),

                    shipment.getCommodity(),

                    shipment.getOriginCountry(),

                    shipment.getOriginCityPort(),

                    shipment.getDestinationCountry(),

                    shipment.getDestinationCityPort(),

                    shipment.getGrossWeightKg(),

                    shipment.getVolumetricWeightKg(),

                    shipment.getChargeableWeightKg(),

                    shipment.getPackages(),

                    shipment.getAirlineUsed(),

                    shipment.getServiceType(),

                    shipment.getOperatorName(),

                    shipment.getEtd(),

                    shipment.getEta(),

                    shipment.getSupplierCost(),

                    shipment.getOtherCost(),

                    shipment.getTotalCost(),

                    shipment.getAmountBilledToClient(),

                    shipment.getAmountPaidByClient(),

                    shipment.getAmountRemaining(),

                    shipment.getOtherExpenses(),

                    shipment.getAmountPaidToSupply(),

                    shipment.getNetIncome(),

                    shipment.getMarginPercent(),

                    shipment.getInvoiceNo(),

                    shipment.getPaymentStatus(),

                    shipment.getOwnerName(),

                    shipment.getNextAction(),

                    shipment.getNextActionDate(),

                    shipment.getNotes(),

                    shipment.getCurrency(),

                    shipment.getCreatedAt(),

                    shipment.getUpdatedAt()
            );
        }
    }

    public record UpdateFlightNumberRequest(
            @NotBlank String flightNumber
    ) {
    }

    public record UpdateWeightRequest(
            @jakarta.validation.constraints.PositiveOrZero
            int weightKg
    ) {
    }

    public record UpdateNotificationEmailRequest(
            @jakarta.validation.constraints.Email
            String notificationEmail
    ) {
    }
}