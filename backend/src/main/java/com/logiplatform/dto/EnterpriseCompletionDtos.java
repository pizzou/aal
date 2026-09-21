package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EnterpriseCompletionDtos {
    private EnterpriseCompletionDtos() {}

    // Existing platform / security DTOs retained for backward compatibility.
    public record MfaSetupResponse(String secret, String otpauthUri, boolean enabled) {}
    public record MfaVerifyRequest(@NotBlank String code) {}
    public record MfaStatusResponse(boolean configured, boolean enabled, Instant verifiedAt) {}

    public record FxRateRequest(
            @NotNull LocalDate rateDate,
            @NotBlank String baseCurrency,
            @NotBlank String quoteCurrency,
            @NotNull @Positive BigDecimal rate,
            String source) {}

    public record FxRateResponse(
            UUID id,
            LocalDate rateDate,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal rate,
            String source) {}

    public record PeriodRequest(
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            String notes) {}

    public record PeriodResponse(
            UUID id,
            LocalDate periodStart,
            LocalDate periodEnd,
            String status,
            Instant closedAt,
            String notes) {}

    public record AdjustmentRequest(
            @NotBlank String adjustmentNo,
            @NotBlank String adjustmentType,
            UUID invoiceId,
            UUID shipmentId,
            @NotNull @PositiveOrZero BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String reason) {}

    public record AdjustmentResponse(
            UUID id,
            String adjustmentNo,
            String adjustmentType,
            UUID invoiceId,
            UUID shipmentId,
            BigDecimal amount,
            String currency,
            String reason,
            String status) {}

    public record GeofenceRequest(
            @NotBlank String name,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude,
            @Min(1) int radiusM) {}

    public record GeofenceResponse(
            UUID id,
            String name,
            double latitude,
            double longitude,
            int radiusM,
            boolean active) {}

    public record GeofenceEvaluation(double distanceM, boolean inside, String geofenceName) {}

    public record LegMilestoneRequest(
            @NotBlank String milestoneType,
            String location,
            Instant plannedAt,
            Instant actualAt,
            String status,
            String notes) {}

    public record LegDocumentRequest(
            @NotBlank String documentType,
            @NotBlank String documentUri,
            boolean customerVisible) {}

    public record LegUpdateRequest(
            String carrierReference,
            String equipmentReference,
            Instant actualDeparture,
            Instant actualArrival,
            String status,
            String notes) {}

    public record LegCostRequest(
            @NotBlank String description,
            @NotNull @PositiveOrZero BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String supplier) {}

    public record IntegrationRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            @NotBlank String protocol,
            @NotBlank String baseUrl,
            boolean enabled) {}

    public record IntegrationResponse(
            UUID id,
            String code,
            String displayName,
            String protocol,
            String baseUrl,
            boolean enabled,
            String healthStatus,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastError) {}

    public record NotificationQueueRequest(
            UUID shipmentId,
            @NotBlank String channel,
            @NotBlank String recipient,
            @NotBlank String eventType,
            @NotBlank String subject,
            @NotBlank String body) {}

    // New enterprise completion DTOs.
    public record CarrierContractRequest(
            UUID carrierId,
            @NotBlank String contractNo,
            @NotBlank String carrierName,
            @NotBlank String mode,
            String laneCode,
            String currency,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            String paymentTerms,
            String terms,
            String rateSnapshotJson) {}

    public record CarrierSettlementRequest(
            UUID carrierId,
            @NotBlank String carrierName,
            UUID shipmentId,
            UUID tenderId,
            @NotBlank String settlementNo,
            @NotNull @PositiveOrZero BigDecimal amount,
            @NotBlank String currency,
            String notes) {}

    public record FinanceNoteRequest(
            @NotBlank String noteNo,
            @NotBlank String noteType,
            UUID invoiceId,
            UUID shipmentId,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String reason) {}

    public record TaxRuleRequest(
            @NotBlank String code,
            @NotBlank String taxName,
            @NotNull @PositiveOrZero BigDecimal rate,
            @NotNull @PositiveOrZero BigDecimal withholdingRate,
            String currency,
            @NotNull LocalDate validFrom,
            LocalDate validUntil) {}

    public record SlaEvaluationRequest(UUID shipmentId, String eventCode, String owner) {}

    public record EscalationRequest(
            @NotNull UUID exceptionId,
            @NotNull @Positive Integer levelNo,
            @NotBlank String targetOwner,
            Instant dueAt) {}

    public record OceanShippingInstructionRequest(
            @NotNull UUID bookingId,
            String shipperJson,
            String consigneeJson,
            String notifyPartyJson,
            String cargoJson,
            String action) {}

    public record OceanBillOfLadingRequest(
            @NotNull UUID bookingId,
            @NotBlank String billType,
            @NotBlank String billNumber,
            String action,
            String eblReference) {}

    public record DocumentReviewRequest(@NotBlank String action, String reason) {}

    public record CustomsSubmissionRequest(
            @NotNull UUID declarationId,
            @NotBlank String idempotencyKey,
            String externalReference) {}

    public record RoutePlanRequest(
            UUID shipmentId,
            UUID tripId,
            @NotBlank String mode,
            @NotBlank String origin,
            @NotBlank String destination,
            @NotBlank String routeJson,
            BigDecimal distanceKm,
            Integer durationMinutes) {}

    public record MobileDeviceRequest(
            @NotBlank String deviceId,
            @NotBlank String deviceType,
            String platform,
            String appVersion,
            UUID userId) {}

    public record PushSubscriptionRequest(
            @NotBlank String deviceId,
            @NotBlank String provider,
            @NotBlank String token) {}

    public record MobileSyncApplyRequest(
            @NotBlank String deviceId,
            @NotBlank String operationId,
            @NotBlank String entityType,
            UUID entityId,
            @NotBlank String payloadJson) {}

    public record AccountingExportRequest(UUID periodId, @NotBlank String exportType) {}

    public record AutomationTickRequest(String trigger, UUID shipmentId) {}
}
