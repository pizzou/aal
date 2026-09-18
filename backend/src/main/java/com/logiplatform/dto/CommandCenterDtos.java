package com.logiplatform.dto;

import jakarta.validation.constraints.*; import java.math.BigDecimal; import java.time.*; import java.util.List; import java.util.UUID;
public final class CommandCenterDtos {
 private CommandCenterDtos(){}
 public record QuoteRequest(@NotBlank String quoteId,@NotNull LocalDate quoteDate,@NotBlank String client,@NotBlank String route,@NotBlank String serviceType,String commodity,@Positive BigDecimal chargeableWeightKg,@PositiveOrZero BigDecimal supplierCost,@PositiveOrZero BigDecimal otherCost,@NotNull BigDecimal markupPercent,LocalDate validUntil,String status,String owner,LocalDate followUpDate,String notes,String pricingMode){}
 public record InvoiceRequest(@NotBlank String invoiceNo,@NotNull LocalDate issueDate,@NotBlank String client,UUID shipmentId,@NotBlank String currency,@NotNull @Positive BigDecimal invoiceAmount,@NotNull LocalDate dueDate,String owner,String notes){}
 public record ClientRequest(@NotBlank String clientId,@NotBlank String clientCompany,String contactPerson,String phone,String email,String industry,String country,String city,String leadSource,String clientStatus,String relationshipOwner,LocalDate nextFollowUp,String notes){}
 public record PartnerRequest(@NotBlank String partnerId,String country,@NotBlank String company,String contactPerson,String phone,String email,String services,String cityPortAirport,String paymentTerms,@Min(1) @Max(5) Integer rating,String status,LocalDate lastVerified,String notes){}
 public record TaskRequest(@NotBlank String taskId,@NotNull LocalDate createdDate,String department,String relatedReference,@NotBlank String task,String priority,String owner,LocalDate dueDate,String status,LocalDate completionDate,String notes){}
 public record DailyOperationsResponse(
        LocalDate date,
        DailySummary summary,
        FleetSummary fleet,
        List<RoutingJob> jobs,
        List<RouteSummary> routes,
        List<OperationalException> exceptions
 ) {}
 public record DailySummary(
        int totalJobs, int scheduledJobs, int inTransitJobs, int deliveredJobs,
        int delayedJobs, int exceptionJobs, int unassignedJobs, int routeCount
 ) {}
 public record FleetSummary(
        int totalVehicles, int availableVehicles, int onTripVehicles, int maintenanceVehicles,
        int totalDrivers, int availableDrivers, int onTripDrivers
 ) {}
 public record RoutingJob(
        UUID shipmentId, String referenceCode, String clientName, String serviceType,
        String transportMode, String origin, String destination, String status,
        Instant etd, Instant eta, String carrier, String carrierReference,
        String driverName, String vehicleRegistration, UUID tripId, String priority,
        boolean delayed, boolean unassigned, String nextAction
 ) {}
 public record RouteSummary(
        UUID tripId, String origin, String destination, Instant scheduledDeparture,
        String status, String driverName, String vehicleRegistration, int shipmentCount, List<UUID> shipmentIds
 ) {}
 public record OperationalException(
        String type, String severity, String reference, String message, UUID shipmentId, UUID tripId
 ) {}
 public record ExpenseRequest(@NotBlank String expenseId,@NotNull LocalDate expenseDate,String type,String category,UUID shipmentId,String client,String vendorPayee,String description,@NotBlank String currency,@NotNull @Positive BigDecimal originalAmount,BigDecimal exchangeRateToUsd,String paymentMethod,String status,String approvedBy){}
}
