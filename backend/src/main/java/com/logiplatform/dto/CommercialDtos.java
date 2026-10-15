package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class CommercialDtos {
    private CommercialDtos() {
    }

    public record QuoteRequest(@NotBlank @Size(max = 80) String quoteId, @NotNull LocalDate quoteDate,
            @NotBlank @Size(max = 200) String client, @NotBlank @Size(max = 300) String route,
            @NotBlank @Size(max = 80) String serviceType, @Size(max = 300) String commodity,
            @PositiveOrZero BigDecimal chargeableWeightKg, @PositiveOrZero BigDecimal supplierCost,
            @PositiveOrZero BigDecimal otherCost, @PositiveOrZero BigDecimal markupPercent,
            @PositiveOrZero BigDecimal quotedAmount, LocalDate validUntil, String status,
            @Size(max = 160) String owner, LocalDate followUpDate, @Size(max = 4000) String notes,
            @Size(max = 40) String pricingMode, @Email @Size(max = 254) String customerEmail,
            @Size(max = 160) String customerContactName, @Size(max = 80) String customerPhone,
            @Size(min = 3, max = 3) String currency, @Size(max = 20) String incoterm,
            @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal taxRate,
            @PositiveOrZero BigDecimal taxAmount, @PositiveOrZero BigDecimal customsCost,
            @PositiveOrZero BigDecimal insuranceCost, @Size(max = 1000) String customerCreditTerms) {
    }

    public record QuoteResponse(UUID id, String quoteId, LocalDate quoteDate, String client, String route,
            String serviceType, String commodity, BigDecimal chargeableWeightKg, BigDecimal supplierCost,
            BigDecimal otherCost, BigDecimal markupPercent, BigDecimal quotedAmount, BigDecimal expectedProfit,
            LocalDate validUntil, String status, String owner, LocalDate followUpDate, String notes, String pricingMode,
            java.time.Instant createdAt, String currency, String incoterm, BigDecimal taxRate, BigDecimal taxAmount,
            BigDecimal customsCost, BigDecimal insuranceCost, String customerCreditTerms, BigDecimal lockedAmount,
            String lockedCurrency, java.time.Instant priceLockedAt, java.util.UUID acceptedVersionId) {
        public static QuoteResponse from(com.logiplatform.model.CommercialQuote x) {
            return new QuoteResponse(x.getId(), x.getQuoteId(), x.getQuoteDate(), x.getClient(), x.getRoute(),
                    x.getServiceType(), x.getCommodity(), x.getChargeableWeightKg(), x.getSupplierCost(),
                    x.getOtherCost(), x.getMarkupPercent(), x.getQuotedAmount(), x.getExpectedProfit(),
                    x.getValidUntil(), x.getStatus(), x.getOwner(), x.getFollowUpDate(), x.getNotes(),
                    x.getPricingMode(), x.getCreatedAt(), x.getCurrency(), x.getIncoterm(), x.getTaxRate(),
                    x.getTaxAmount(), x.getCustomsCost(), x.getInsuranceCost(), x.getCustomerCreditTerms(),
                    x.getLockedAmount(), x.getLockedCurrency(), x.getPriceLockedAt(), x.getAcceptedVersionId());
        }
    }

    public record QuoteToShipmentResponse(UUID quoteId, String quoteReference, UUID shipmentId,
            String shipmentReference, String status, String message) {
    }

    public record InvoiceRequest(String invoiceNo, @NotNull LocalDate issueDate, String client, UUID shipmentId,
            @NotBlank String currency, @Positive BigDecimal invoiceAmount, LocalDate dueDate, String owner,
            String notes) {
    }

    public record InvoiceResponse(UUID id, String invoiceNo, LocalDate issueDate, String client, UUID shipmentId,
            String currency, BigDecimal invoiceAmount, BigDecimal amountPaid, BigDecimal balance, LocalDate dueDate,
            LocalDate lastFollowUp, LocalDate nextFollowUp, String owner, String notes, long daysOverdue,
            String agingBucket, String status) {
        public static InvoiceResponse from(com.logiplatform.model.CommercialInvoice x) {
            return new InvoiceResponse(x.getId(), x.getInvoiceNo(), x.getIssueDate(), x.getClient(), x.getShipmentId(),
                    x.getCurrency(), x.getInvoiceAmount(), x.getAmountPaid(), x.getBalance(), x.getDueDate(),
                    x.getLastFollowUp(), x.getNextFollowUp(), x.getOwner(), x.getNotes(), x.getDaysOverdue(),
                    x.getAgingBucket(), x.getStatus());
        }
    }

    public record PaymentRequest(@NotNull @Positive BigDecimal amount, @NotBlank String currency,
            @NotBlank String idempotencyKey, String reference, UUID incomeSourceId) {
    }

    public record ClientRequest(@Size(max = 80) String clientId,
            @NotBlank @Size(max = 200) String clientCompany, @Size(max = 160) String contactPerson,
            @Size(max = 80) String phone, @Email @Size(max = 254) String email,
            @Size(max = 120) String industry, @Size(max = 120) String country,
            @Size(max = 120) String city, @Size(max = 120) String leadSource,
            @Size(max = 40) String clientStatus, @Size(max = 160) String relationshipOwner,
            LocalDate nextFollowUp, @Size(max = 4000) String notes) {
    }

    public record ClientResponse(UUID id, String clientId, String clientCompany, String contactPerson, String phone,
            String email, String industry, String country, String city, String leadSource, String clientStatus,
            String relationshipOwner, LocalDate nextFollowUp, String notes) {
        public static ClientResponse from(com.logiplatform.model.ClientRecord x) {
            return new ClientResponse(x.getId(), x.getClientId(), x.getClientCompany(), x.getContactPerson(),
                    x.getPhone(), x.getEmail(), x.getIndustry(), x.getCountry(), x.getCity(), x.getLeadSource(),
                    x.getClientStatus(), x.getRelationshipOwner(), x.getNextFollowUp(), x.getNotes());
        }
    }

    public record PartnerRequest(@NotBlank String partnerId, String country, String company, String contactPerson,
            String phone, String email, String services, String cityPortAirport, String paymentTerms,
            @Min(1) @Max(5) Integer rating, String status, LocalDate lastVerified, String notes) {
    }

    public record PartnerResponse(UUID id, String partnerId, String country, String company, String contactPerson,
            String phone, String email, String services, String cityPortAirport, String paymentTerms, Integer rating,
            String status, LocalDate lastVerified, String notes) {
        public static PartnerResponse from(com.logiplatform.model.PartnerRecord x) {
            return new PartnerResponse(x.getId(), x.getPartnerId(), x.getCountry(), x.getCompany(),
                    x.getContactPerson(), x.getPhone(), x.getEmail(), x.getServices(), x.getCityPortAirport(),
                    x.getPaymentTerms(), x.getRating(), x.getStatus(), x.getLastVerified(), x.getNotes());
        }
    }

    public record TaskRequest(@NotBlank String taskId, @NotNull LocalDate createdDate, String department,
            String relatedReference, @NotBlank String task, String priority, String owner, LocalDate dueDate,
            String status, LocalDate completionDate, String notes) {
    }

    public record TaskResponse(UUID id, String taskId, LocalDate createdDate, String department,
            String relatedReference, String task, String priority, String owner, LocalDate dueDate, String status,
            LocalDate completionDate, String notes, long daysRemaining, boolean overdue) {
        public static TaskResponse from(com.logiplatform.model.TaskRecord x) {
            return new TaskResponse(x.getId(), x.getTaskId(), x.getCreatedDate(), x.getDepartment(),
                    x.getRelatedReference(), x.getTask(), x.getPriority(), x.getOwner(), x.getDueDate(), x.getStatus(),
                    x.getCompletionDate(), x.getNotes(), x.getDaysRemaining(), x.isOverdue());
        }
    }

    public record ExpenseRequest(@NotBlank String expenseId, @NotNull LocalDate expenseDate, String type,
            String category, UUID shipmentId, String client, String vendorPayee, String description,
            @NotBlank String currency, @Positive BigDecimal originalAmount, @Positive BigDecimal exchangeRateToUsd,
            String paymentMethod, String status, String approvedBy) {
    }

    public record ExpenseResponse(UUID id, String expenseId, LocalDate expenseDate, String type, String category,
            UUID shipmentId, String client, String vendorPayee, String description, String currency,
            BigDecimal originalAmount, BigDecimal exchangeRateToUsd, BigDecimal usdEquivalent, String paymentMethod,
            String status, String approvedBy) {
        public static ExpenseResponse from(com.logiplatform.model.ExpenseRecord x) {
            return new ExpenseResponse(x.getId(), x.getExpenseId(), x.getExpenseDate(), x.getType(), x.getCategory(),
                    x.getShipmentId(), x.getClient(), x.getVendorPayee(), x.getDescription(), x.getCurrency(),
                    x.getOriginalAmount(), x.getExchangeRateToUsd(), x.getUsdEquivalent(), x.getPaymentMethod(),
                    x.getStatus(), x.getApprovedBy());
        }
    }
}
