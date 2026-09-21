package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AdvancedLogisticsDtos {
    private AdvancedLogisticsDtos() {}

    public record RatePreviewRequest(
            @NotBlank String mode,
            String originCode,
            String destinationCode,
            String laneCode,
            UUID clientId,
            UUID carrierId,
            @NotNull @PositiveOrZero BigDecimal weightKg,
            BigDecimal volumeM3,
            String commodity,
            String currency,
            List<ChargeInput> charges
    ) {}

    public record ChargeInput(
            @NotBlank String code,
            String category,
            String description,
            @NotNull @PositiveOrZero BigDecimal amount,
            BigDecimal buyAmount,
            String currency
    ) {}

    public record QuoteChargeRequest(
            @NotBlank String code,
            @NotBlank String category,
            @NotBlank String description,
            @NotNull @PositiveOrZero BigDecimal sellAmount,
            @NotNull @PositiveOrZero BigDecimal buyAmount,
            BigDecimal quantity,
            BigDecimal unitRate,
            String currency
    ) {}

    public record ReadinessRequest(String trigger) {}

    public record OceanChargeRequest(
            UUID containerId,
            @NotBlank String chargeType,
            @NotNull LocalDate eventDate,
            @NotNull @PositiveOrZero Integer freeDays,
            @NotNull @PositiveOrZero Integer billableDays,
            @NotNull @PositiveOrZero BigDecimal dailyRate,
            String currency,
            String notes
    ) {}

    public record WarehouseBarcodeRequest(
            UUID shipmentId,
            UUID inventoryItemId,
            @NotBlank String barcode,
            String barcodeType
    ) {}

    public record CycleCountRequest(
            @NotNull UUID warehouseId,
            String locationCode,
            @NotNull Instant scheduledAt,
            @NotNull @PositiveOrZero BigDecimal expectedQuantity,
            String countedBy
    ) {}

    public record CustomsLineRequest(
            @NotNull UUID declarationId,
            @NotNull @Positive Integer lineNo,
            @NotBlank String hsCode,
            String description,
            String countryOfOrigin,
            BigDecimal quantity,
            BigDecimal unitValue,
            BigDecimal declaredValue,
            String currency,
            BigDecimal dutyRate,
            BigDecimal taxRate
    ) {}

    public record SupplierBillRequest(
            @NotBlank String supplierName,
            @NotBlank String supplierInvoiceNo,
            UUID shipmentId,
            @NotBlank String currency,
            @NotNull @PositiveOrZero BigDecimal amount,
            @NotNull LocalDate issueDate,
            LocalDate dueDate,
            String notes
    ) {}

    public record BankTransactionRequest(
            @NotBlank String bankAccount,
            @NotNull LocalDate transactionDate,
            String reference,
            @NotNull BigDecimal amount,
            @NotBlank String currency,
            @NotBlank String direction
    ) {}

    public record CarrierPerformanceRequest(
            UUID carrierId,
            @NotBlank String carrierName,
            UUID shipmentId,
            @NotBlank String eventType,
            Instant plannedAt,
            Instant actualAt,
            Integer varianceMinutes,
            BigDecimal score,
            String notes
    ) {}

    public record FeedbackRequest(
            UUID shipmentId,
            UUID quoteId,
            @Min(1) @Max(5) Integer rating,
            String category,
            String comment,
            @Email String contactEmail
    ) {}

    public record WebhookRequest(
            @NotBlank String eventType,
            @NotBlank String endpointUrl,
            String secret
    ) {}

    public record MobileSyncRequest(
            @NotBlank String deviceId,
            @NotBlank String operationId,
            @NotBlank String entityType,
            UUID entityId,
            @NotBlank String payloadJson
    ) {}

    public record WorkflowRuleRequest(
            @NotBlank String ruleCode,
            @NotBlank String eventType,
            @NotBlank String conditionJson,
            @NotBlank String actionJson
    ) {}

    public record DocumentSignatureRequest(
            @NotNull UUID documentId,
            @NotBlank String signerName,
            @Email String signerEmail
    ) {}

    public record AutomationRequest(
            @NotBlank String eventType,
            UUID entityId
    ) {}

    public record ApiResult(
            String status,
            String message,
            Map<String,Object> data
    ) {}

    public record PricingRuleRequest(
            @NotBlank String ruleCode,
            Integer priority,
            String mode,
            String laneCode,
            UUID clientId,
            UUID carrierId,
            BigDecimal minWeightKg,
            BigDecimal maxWeightKg,
            @NotNull @PositiveOrZero BigDecimal minCharge,
            @NotNull @PositiveOrZero BigDecimal ratePerKg,
            @NotNull @PositiveOrZero BigDecimal markupPercent,
            @NotNull @PositiveOrZero BigDecimal fuelPercent,
            @NotNull @PositiveOrZero BigDecimal taxPercent,
            String currency,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            String conditionsJson
    ) {}

    public record CustomerRateCardRequest(
            @NotNull UUID clientId,
            @NotBlank String cardCode,
            @NotBlank String mode,
            String laneCode,
            @NotNull @PositiveOrZero BigDecimal baseRatePerKg,
            @NotNull @PositiveOrZero BigDecimal minCharge,
            @NotNull @PositiveOrZero BigDecimal fuelPercent,
            @NotNull @PositiveOrZero BigDecimal securityPercent,
            String currency,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            String terms
    ) {}

    public record CarrierBuyRateRequest(
            UUID carrierId,
            @NotBlank String carrierName,
            @NotBlank String mode,
            String laneCode,
            @NotNull @PositiveOrZero BigDecimal baseRatePerKg,
            @NotNull @PositiveOrZero BigDecimal minCharge,
            @NotNull @PositiveOrZero BigDecimal fuelPercent,
            @NotNull @PositiveOrZero BigDecimal securityPercent,
            String currency,
            @NotNull LocalDate validFrom,
            LocalDate validUntil,
            String contractReference
    ) {}


    public record OceanFreeTimeRuleRequest(
            String carrierName,
            String portCode,
            String containerType,
            @NotNull @PositiveOrZero Integer freeDays,
            @NotNull @PositiveOrZero BigDecimal demurragePerDay,
            @NotNull @PositiveOrZero BigDecimal detentionPerDay,
            String currency,
            @NotNull LocalDate validFrom,
            LocalDate validUntil
    ) {}

}
