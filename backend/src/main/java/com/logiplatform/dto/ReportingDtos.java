package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReportingDtos {

        private ReportingDtos() {
        }

        public record StatusCount(
                        String status,
                        long count) {
        }

        public record CarrierLeadTime(
                        String carrierName,
                        long deliveredCount,
                        Double avgLeadTimeHours) {
        }

        public record CarrierExceptionRate(
                        String carrierName,
                        long totalShipments,
                        long shipmentsWithExceptions,
                        double exceptionRatePercent) {
        }

        public record VehicleStatusCount(
                        String status,
                        long count) {
        }

        public record TripStats(
                        long completed,
                        long cancelled,
                        long inProgress,
                        long planned) {
        }

        /*
         * ------------------------------------------------------------------------
         * MANAGEMENT REPORT
         * ------------------------------------------------------------------------
         */

        public record ManagementReport(
                        LocalDate from,
                        LocalDate to,
                        String currency,
                        boolean mixedCurrencies,

                        OperationsSummary operations,
                        FinancialSummary financial,
                        ReceivablesSummary receivables,
                        SalesPipelineSummary salesPipeline,
                        TaskSummary tasks,
                        FleetSummary fleet,

                        List<CustomerProfitability> customerProfitability,
                        List<CarrierProfitability> carrierProfitability,
                        List<ShipmentProfitability> shipmentProfitability,
                        List<MonthlyTrend> monthlyTrend,
                        List<ReceivablesAging> receivablesAging,
                        List<QuoteStatus> quotationPipeline,
                        List<ExceptionSummary> operationalExceptions) {
        }

        public record OperationsSummary(
                        long totalShipments,
                        long activeShipments,
                        long completedShipments,
                        long departedShipments,
                        long arrivalsToday,
                        long delayedShipments,
                        long exceptionShipments,
                        long unassignedShipments,
                        BigDecimal onTimeRatePercent,
                        BigDecimal completionRatePercent) {
        }

        public record FinancialSummary(
                        BigDecimal invoicedRevenue,
                        BigDecimal collectedRevenue,
                        BigDecimal outstandingReceivables,
                        BigDecimal overdueReceivables,
                        BigDecimal supplierCosts,
                        BigDecimal otherCosts,
                        BigDecimal grossProfit,
                        BigDecimal grossMarginPercent,
                        BigDecimal totalExpenses,
                        BigDecimal netProfit,
                        BigDecimal collectionRatePercent) {
        }

        public record ReceivablesSummary(
                        long invoiceCount,
                        long unpaidInvoices,
                        long partiallyPaidInvoices,
                        long overdueInvoices,
                        BigDecimal invoiced,
                        BigDecimal collected,
                        BigDecimal outstanding,
                        BigDecimal overdue,
                        BigDecimal dueNext30Days,
                        BigDecimal dueToday) {
        }

        public record SalesPipelineSummary(
                        long totalQuotes,
                        long openQuotes,
                        long wonQuotes,
                        long lostQuotes,
                        long expiredQuotes,
                        BigDecimal quotedValue,
                        BigDecimal wonValue,
                        BigDecimal openValue,
                        BigDecimal conversionRatePercent) {
        }

        public record TaskSummary(
                        long openTasks,
                        long dueToday,
                        long overdueTasks) {
        }

        public record FleetSummary(
                        long vehicles,
                        long availableVehicles,
                        long onTripVehicles,
                        long maintenanceVehicles,
                        long drivers,
                        long availableDrivers,
                        long onTripDrivers) {
        }

        public record CustomerProfitability(
                        String customer,
                        long shipments,
                        BigDecimal revenue,
                        BigDecimal supplierCost,
                        BigDecimal otherCost,
                        BigDecimal grossProfit,
                        BigDecimal marginPercent) {
        }

        public record CarrierProfitability(
                        String carrier,
                        long shipments,
                        BigDecimal revenue,
                        BigDecimal supplierCost,
                        BigDecimal otherCost,
                        BigDecimal grossProfit,
                        BigDecimal marginPercent,
                        BigDecimal exceptionRatePercent) {
        }

        public record ShipmentProfitability(
                        String shipmentReference,
                        LocalDate dateOpened,
                        String customer,
                        String carrier,
                        String mode,
                        String currency,
                        BigDecimal chargeableWeightKg,
                        BigDecimal revenue,
                        BigDecimal supplierCost,
                        BigDecimal otherCost,
                        BigDecimal grossProfit,
                        BigDecimal marginPercent,
                        String status) {
        }

        public record MonthlyTrend(
                        String month,
                        long shipments,
                        BigDecimal invoicedRevenue,
                        BigDecimal collectedRevenue,
                        BigDecimal outstandingReceivables,
                        BigDecimal grossProfit) {
        }

        public record ReceivablesAging(
                        String bucket,
                        BigDecimal balance,
                        long invoiceCount) {
        }

        public record QuoteStatus(
                        String status,
                        long count,
                        BigDecimal quotedValue) {
        }

        public record ExceptionSummary(
                        String severity,
                        String type,
                        String reference,
                        String message,
                        String lane,
                        String mode) {
        }

        /*
         * ------------------------------------------------------------------------
         * LEGACY REPORT CONTRACT
         * ------------------------------------------------------------------------
         *
         * Kept so existing consumers of /api/reports/dashboard do not break.
         */

        public record DashboardResponse(
                        List<StatusCount> shipmentsByStatus,
                        List<CarrierLeadTime> carrierLeadTimes,
                        List<CarrierExceptionRate> carrierExceptionRates,
                        List<VehicleStatusCount> fleetUtilization,
                        TripStats tripStats) {
        }

}
