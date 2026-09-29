package com.logiplatform.dto;

import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.model.AwbRecord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class AirCargoDtos {
        private AirCargoDtos() {
        }

        public record FlightIngestRequest(
                        @NotBlank String carrierCode,
                        @NotBlank String carrierName,
                        @NotBlank String flightNumber,
                        @NotBlank String originCode,
                        @NotBlank String destinationCode,
                        @NotNull Instant departureTime,
                        Instant arrivalTime,
                        @NotNull @Positive BigDecimal totalCapacityKg,
                        @NotNull @PositiveOrZero BigDecimal availableCapacityKg) {
        }

        public record FlightSearchRequest(
                        @NotBlank String origin,
                        @NotBlank String destination,
                        Instant from,
                        Instant to,
                        @NotNull @Positive BigDecimal weightKg) {
        }

        public record BookRequest(
                        @NotNull UUID shipmentId,
                        @NotBlank String carrierCode,
                        @NotBlank String carrierName,
                        @NotBlank String flightNumber,
                        @NotNull Instant departureTime,
                        Instant arrivalTime,
                        @NotBlank String originCode,
                        @NotBlank String destinationCode,
                        @NotNull @Positive BigDecimal weightKg,
                        String serviceLevel,
                        @NotBlank String idempotencyKey,
                        String providerReference,
                        String providerCode) {
        }

        public record AmendBookingRequest(
                        @NotBlank String idempotencyKey,
                        String flightNumber,
                        Instant departureTime,
                        Instant arrivalTime,
                        @Positive BigDecimal weightKg,
                        String serviceLevel) {
        }

        public record CancelBookingRequest(
                        @NotBlank String idempotencyKey,
                        String reason) {
        }

        public record AwbRequest(
                        @NotNull UUID shipmentId,
                        @NotBlank String awbNumber,
                        @NotBlank String awbType,
                        String mawbNumber,
                        String hawbNumber,
                        @NotBlank String shipperName,
                        String shipperAddress,
                        @NotBlank String consigneeName,
                        String consigneeAddress,
                        String issuingAgent,
                        @NotBlank String originAirport,
                        @NotBlank String destinationAirport,
                        @NotNull @Positive Integer pieces,
                        @NotNull @Positive BigDecimal grossWeightKg,
                        @NotNull @Positive BigDecimal chargeableWeightKg,
                        @NotBlank String commodity,
                        String hsCode,
                        String specialHandling,
                        Boolean dangerousGoods) {
        }

        public record CustomsRequest(
                        @NotNull UUID shipmentId,
                        @NotBlank String declarationType,
                        @NotBlank String customsAuthority,
                        String brokerName,
                        String hsCodes,
                        String countryOfOrigin,
                        @NotNull @Positive BigDecimal declaredValue,
                        @NotBlank String currency) {
        }

        public record DocumentRequest(
                        @NotNull UUID shipmentId,
                        @NotBlank String documentType,
                        @NotBlank String templateCode,
                        String fileUri,
                        String contentHash) {
        }

        public record FlightResponse(
                        UUID id,
                        String carrierCode,
                        String carrierName,
                        String flightNumber,
                        String origin,
                        String destination,
                        Instant departure,
                        Instant arrival,
                        BigDecimal totalCapacityKg,
                        BigDecimal availableCapacityKg,
                        String status,
                        String source,
                        String providerCode,
                        String providerReference,
                        String rateId,
                        String rateName,
                        String currency,
                        BigDecimal totalPrice,
                        boolean bookable,
                        String availableReason) {
                public static FlightResponse from(AirCargoFlight f) {
                        return new FlightResponse(
                                        f.getId(),
                                        f.getCarrierCode(),
                                        f.getCarrierName(),
                                        f.getFlightNumber(),
                                        f.getOriginCode(),
                                        f.getDestinationCode(),
                                        f.getDepartureTime(),
                                        f.getArrivalTime(),
                                        f.getTotalCapacityKg(),
                                        f.getAvailableCapacityKg(),
                                        f.getStatus(),
                                        f.getSource(),
                                        f.getProviderCode(),
                                        f.getProviderReference(),
                                        f.getRateId(),
                                        f.getRateName(),
                                        f.getCurrency(),
                                        f.getTotalPrice(),
                                        f.isBookable(),
                                        f.getAvailableReason());
                }
        }

        public record BookingResponse(
                        UUID id,
                        UUID shipmentId,
                        String carrierCode,
                        String carrierName,
                        String flightNumber,
                        String status,
                        String provider,
                        String providerReference,
                        String confirmationNumber,
                        BigDecimal requestedWeightKg,
                        BigDecimal confirmedWeightKg,
                        Instant departureTime,
                        Instant arrivalTime,
                        String serviceLevel,
                        String cancellationReason,
                        Instant cancelledAt,
                        Instant createdAt,
                        Instant updatedAt,
                        String workflowStage,
                        String workflowMessage) {
                public static BookingResponse from(AirCargoBooking b) {
                        String status = b.getStatus() == null ? "UNKNOWN"
                                        : b.getStatus().toUpperCase(java.util.Locale.ROOT);
                        String workflowStage;
                        String workflowMessage;
                        if ("CONFIRMED".equals(status)) {
                                workflowStage = "CONFIRMED";
                                workflowMessage = "The airline/provider has confirmed this booking.";
                        } else if ("PENDING_PROVIDER".equals(status) || "UNKNOWN".equals(status)) {
                                workflowStage = "AWAITING_PROVIDER";
                                workflowMessage = "The booking was sent to the configured provider and is awaiting confirmation or reconciliation.";
                        } else if ("REQUESTED".equals(status)
                                        && "INTERNAL_CAPACITY".equalsIgnoreCase(b.getProvider())) {
                                workflowStage = "AAL_OPERATIONS_REVIEW";
                                workflowMessage = "Stored in the AAL Airline Booking Desk. No external airline request was sent because no live booking provider was available for this request.";
                        } else if ("FAILED".equals(status)) {
                                workflowStage = "FAILED";
                                workflowMessage = "The booking request failed and requires integration or operations review.";
                        } else if ("CANCELLED".equals(status)) {
                                workflowStage = "CANCELLED";
                                workflowMessage = "The booking has been cancelled.";
                        } else {
                                workflowStage = status;
                                workflowMessage = "The booking is stored in the AAL booking register.";
                        }

                        return new BookingResponse(
                                        b.getId(),
                                        b.getShipmentId(),
                                        b.getCarrierCode(),
                                        b.getCarrierName(),
                                        b.getFlightNumber(),
                                        b.getStatus(),
                                        b.getProvider(),
                                        b.getProviderReference(),
                                        b.getConfirmationNumber(),
                                        b.getRequestedWeightKg(),
                                        b.getConfirmedWeightKg(),
                                        b.getDepartureTime(),
                                        b.getArrivalTime(),
                                        b.getServiceLevel(),
                                        b.getCancellationReason(),
                                        b.getCancelledAt(),
                                        b.getCreatedAt(),
                                        b.getUpdatedAt(),
                                        workflowStage,
                                        workflowMessage);
                }
        }

        public record AwbResponse(
                        UUID id,
                        UUID shipmentId,
                        String awbNumber,
                        String awbType,
                        String mawbNumber,
                        String hawbNumber,
                        String validationStatus,
                        String submissionStatus,
                        String carrierReference,
                        String documentUri,
                        String cargoAcceptanceStatus,
                        String eawbStatus,
                        String oneRecordReference) {
                public static AwbResponse from(AwbRecord a) {
                        return new AwbResponse(
                                        a.getId(),
                                        a.getShipmentId(),
                                        a.getAwbNumber(),
                                        a.getAwbType(),
                                        a.getMawbNumber(),
                                        a.getHawbNumber(),
                                        a.getValidationStatus(),
                                        a.getSubmissionStatus(),
                                        a.getCarrierReference(),
                                        a.getDocumentUri(),
                                        a.getCargoAcceptanceStatus(),
                                        a.getEawbStatus(),
                                        a.getOneRecordReference());
                }
        }
}
