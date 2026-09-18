package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EnterpriseCompletionDtos {
    private EnterpriseCompletionDtos() {}

    public record MfaSetupResponse(String secret, String otpauthUri, boolean enabled) {}
    public record MfaVerifyRequest(@NotBlank String code) {}
    public record MfaStatusResponse(boolean configured, boolean enabled, Instant verifiedAt) {}

    public record FxRateRequest(@NotNull LocalDate rateDate, @NotBlank String baseCurrency,
                                @NotBlank String quoteCurrency, @NotNull @Positive BigDecimal rate, String source) {}
    public record FxRateResponse(UUID id, LocalDate rateDate, String baseCurrency, String quoteCurrency,
                                 BigDecimal rate, String source) {}

    public record PeriodRequest(@NotNull LocalDate periodStart, @NotNull LocalDate periodEnd, String notes) {}
    public record PeriodResponse(UUID id, LocalDate periodStart, LocalDate periodEnd, String status,
                                 Instant closedAt, String notes) {}

    public record AdjustmentRequest(@NotBlank String adjustmentNo, @NotBlank String adjustmentType,
                                    UUID invoiceId, UUID shipmentId, @NotNull @Positive BigDecimal amount,
                                    @NotBlank String currency, @NotBlank String reason) {}
    public record AdjustmentResponse(UUID id, String adjustmentNo, String adjustmentType, UUID invoiceId,
                                     UUID shipmentId, BigDecimal amount, String currency, String reason, String status) {}

    public record GeofenceRequest(@NotBlank String name, @DecimalMin("-90") @DecimalMax("90") double latitude,
                                  @DecimalMin("-180") @DecimalMax("180") double longitude, @Min(1) int radiusM) {}
    public record GeofenceResponse(UUID id, String name, double latitude, double longitude, int radiusM, boolean active) {}
    public record GeofenceEvaluation(double distanceM, boolean inside, String geofenceName) {}

    public record LegMilestoneRequest(@NotBlank String milestoneType, String location, Instant plannedAt,
                                      Instant actualAt, String status, String notes) {}
    public record LegDocumentRequest(@NotBlank String documentType, @NotBlank String documentUri,
                                     boolean customerVisible) {}
    public record LegUpdateRequest(String carrierReference, String equipmentReference, Instant actualDeparture, Instant actualArrival, String status, String notes) {}

    public record LegCostRequest(@NotBlank String description, @NotNull @PositiveOrZero BigDecimal amount,
                                 @NotBlank String currency, String supplier) {}

    public record IntegrationRequest(@NotBlank String code, @NotBlank String displayName,
                                     @NotBlank String protocol, String baseUrl, boolean enabled) {}
    public record IntegrationResponse(UUID id, String code, String displayName, String protocol,
                                      String baseUrl, boolean enabled, String healthStatus,
                                      Instant lastSuccessAt, Instant lastFailureAt, String lastError) {}

    public record NotificationQueueRequest(UUID shipmentId, @NotBlank String channel, String recipient,
                                           @NotBlank String eventType, String subject, @NotBlank String body) {}
}
