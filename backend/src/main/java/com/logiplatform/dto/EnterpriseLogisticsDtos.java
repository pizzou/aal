package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class EnterpriseLogisticsDtos {
    private EnterpriseLogisticsDtos() {}
    public record QuoteRequest(@NotBlank String quoteNumber,UUID clientId,UUID shipmentId,@NotBlank @Size(min=3,max=3) String currency,Instant validUntil,BigDecimal targetMarginPercent,String terms) {}
    public record QuoteLineRequest(@NotNull @Min(1) Integer lineNo,@NotBlank String description,String mode,@NotNull @DecimalMin("0") BigDecimal quantity,BigDecimal unitPrice,BigDecimal costAmount,BigDecimal sellAmount,@NotBlank @Size(min=3,max=3) String currency) {}
    public record TaskRequest(UUID shipmentId,@NotBlank String title,String description,String taskType,@NotBlank String priority,UUID assignedTo,Instant dueAt) {}
    public record MilestoneRequest(@NotBlank String code,@NotBlank String name,@Min(0) Integer sequenceNo,Instant plannedAt,Instant estimatedAt,@NotBlank String status,String source,String notes) {}
    public record ClaimRequest(@NotNull UUID shipmentId,@NotBlank String claimNumber,@NotBlank String claimType,String responsibleParty,@DecimalMin("0") BigDecimal claimedAmount,@NotBlank @Size(min=3,max=3) String currency,String description) {}
}
