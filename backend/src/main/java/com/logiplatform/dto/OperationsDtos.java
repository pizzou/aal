package com.logiplatform.dto;

import com.logiplatform.model.DispatchStop;
import com.logiplatform.model.OperationalException;
import com.logiplatform.model.ProofOfDelivery;
import com.logiplatform.model.WarehouseTask;
import com.logiplatform.model.CarrierTender;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OperationsDtos {

    private OperationsDtos() {
    }

    public record StopRequest(
            @NotNull UUID tripId,
            UUID shipmentId,
            @Min(1) int sequenceNo,
            @NotBlank String stopType,
            @NotBlank String address,
            Double latitude,
            Double longitude,
            Instant plannedAt,
            Instant eta,
            BigDecimal distanceFromPreviousKm,
            Integer plannedDurationMinutes,
            String notes) {
    }

    public record StopResponse(
            UUID id,
            UUID tripId,
            UUID shipmentId,
            int sequenceNo,
            String stopType,
            String address,
            Double latitude,
            Double longitude,
            Instant plannedAt,
            Instant eta,
            Instant actualAt,
            String status,
            BigDecimal distanceFromPreviousKm,
            Integer plannedDurationMinutes,
            String notes) {

        public static StopResponse from(DispatchStop stop) {
            return new StopResponse(
                    stop.getId(),
                    stop.getTripId(),
                    stop.getShipmentId(),
                    stop.getSequenceNo(),
                    stop.getStopType(),
                    stop.getAddress(),
                    stop.getLatitude(),
                    stop.getLongitude(),
                    stop.getPlannedAt(),
                    stop.getEta(),
                    stop.getActualAt(),
                    stop.getStatus(),
                    stop.getDistanceFromPreviousKm(),
                    stop.getPlannedDurationMinutes(),
                    stop.getNotes());
        }
    }

    public record PodRequest(
            @NotNull UUID shipmentId,
            UUID tripId,
            @NotBlank String recipientName,
            String recipientPhone,
            String signatureUri,
            String photoUri,
            Instant deliveredAt,
            Double latitude,
            Double longitude,
            String failureReason,
            String notes) {
    }

    public record PodResponse(
            UUID id,
            UUID shipmentId,
            UUID tripId,
            String recipientName,
            String recipientPhone,
            String signatureUri,
            String photoUri,
            Instant deliveredAt,
            Double latitude,
            Double longitude,
            String failureReason,
            String notes) {

        public static PodResponse from(ProofOfDelivery pod) {
            return new PodResponse(
                    pod.getId(),
                    pod.getShipmentId(),
                    pod.getTripId(),
                    pod.getRecipientName(),
                    pod.getRecipientPhone(),
                    pod.getSignatureUri(),
                    pod.getPhotoUri(),
                    pod.getDeliveredAt(),
                    pod.getLatitude(),
                    pod.getLongitude(),
                    pod.getFailureReason(),
                    pod.getNotes());
        }
    }

    public record ExceptionRequest(
            UUID shipmentId,
            UUID tripId,
            UUID vehicleId,
            @NotBlank String type,
            @NotBlank String severity,
            String owner,
            @NotBlank String description) {
    }

    public record ExceptionResponse(
            UUID id,
            UUID shipmentId,
            UUID tripId,
            UUID vehicleId,
            String type,
            String severity,
            String status,
            String owner,
            String description,
            String actionTaken,
            String resolution,
            Instant createdAt,
            Instant resolvedAt) {

        public static ExceptionResponse from(OperationalException exception) {
            return new ExceptionResponse(
                    exception.getId(),
                    exception.getShipmentId(),
                    exception.getTripId(),
                    exception.getVehicleId(),
                    exception.getType(),
                    exception.getSeverity(),
                    exception.getStatus(),
                    exception.getOwner(),
                    exception.getDescription(),
                    exception.getActionTaken(),
                    exception.getResolution(),
                    exception.getCreatedAt(),
                    exception.getResolvedAt());
        }
    }

    public record ResolveExceptionRequest(
            @NotBlank String resolution,
            String actionTaken) {
    }

    public record WarehouseTaskRequest(
            @NotNull UUID warehouseId,
            UUID shipmentId,
            UUID inventoryItemId,
            @NotBlank String taskType,
            @PositiveOrZero Integer quantity,
            String sourceLocation,
            String destinationLocation,
            String assignedTo,
            Instant dueAt,
            String notes) {
    }

    public record WarehouseTaskResponse(
            UUID id,
            UUID warehouseId,
            UUID shipmentId,
            UUID inventoryItemId,
            String taskType,
            String status,
            Integer quantity,
            String sourceLocation,
            String destinationLocation,
            String assignedTo,
            Instant dueAt,
            Instant completedAt,
            String notes) {

        public static WarehouseTaskResponse from(WarehouseTask task) {
            return new WarehouseTaskResponse(
                    task.getId(),
                    task.getWarehouseId(),
                    task.getShipmentId(),
                    task.getInventoryItemId(),
                    task.getTaskType(),
                    task.getStatus(),
                    task.getQuantity(),
                    task.getSourceLocation(),
                    task.getDestinationLocation(),
                    task.getAssignedTo(),
                    task.getDueAt(),
                    task.getCompletedAt(),
                    task.getNotes());
        }
    }

    public record TenderRequest(
            UUID shipmentId,
            UUID tripId,
            @NotBlank String tenderKey,
            @NotBlank String carrierName,
            String carrierContact,
            @Positive BigDecimal quotedAmount,
            String currency,
            Instant expiresAt,
            String note) {
    }

    public record TenderResponse(
            UUID id,
            UUID shipmentId,
            UUID tripId,
            String tenderKey,
            String carrierName,
            String carrierContact,
            BigDecimal quotedAmount,
            String currency,
            String status,
            Instant expiresAt,
            String responseNote,
            Instant createdAt,
            Instant respondedAt) {

        public static TenderResponse from(CarrierTender tender) {
            return new TenderResponse(
                    tender.getId(),
                    tender.getShipmentId(),
                    tender.getTripId(),
                    tender.getTenderKey(),
                    tender.getCarrierName(),
                    tender.getCarrierContact(),
                    tender.getQuotedAmount(),
                    tender.getCurrency(),
                    tender.getStatus(),
                    tender.getExpiresAt(),
                    tender.getResponseNote(),
                    tender.getCreatedAt(),
                    tender.getRespondedAt());
        }
    }

    public record RoutePoint(
            String address,
            Double latitude,
            Double longitude) {
    }

    public record RoutePlanResponse(
            UUID tripId,
            double totalDistanceKm,
            int totalDurationMinutes,
            List<StopResponse> stops) {
    }

    public record FleetLiveResponse(
            UUID vehicleId,
            String registrationNumber,
            String vehicleStatus,
            UUID driverId,
            String driverName,
            UUID tripId,
            String tripStatus,
            Double latitude,
            Double longitude,
            Double speedKmh,
            Instant recordedAt) {
    }
}
