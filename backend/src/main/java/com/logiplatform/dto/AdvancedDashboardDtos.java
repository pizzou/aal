package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class AdvancedDashboardDtos {
    private AdvancedDashboardDtos() {}

    public record Response(
            LocalDate asOf,
            OperationsKpi operations,
            FinancialKpi financial,
            FleetKpi fleet,
            List<ModeMetric> modeMix,
            List<StatusMetric> statusMix,
            List<TrendPoint> trend,
            List<LaneMetric> topLanes,
            List<DashboardException> exceptions,
            List<DashboardAction> actions,
            OperatingKpis operatingKpis,
            List<MonthlyFinancialPoint> monthlyFinancial,
            List<AgingMetric> receivablesAging,
            List<QuotationStatusMetric> quotationStatus
    ) {}

    public record OperationsKpi(
            int totalShipments,
            int activeShipments,
            int deliveredShipments,
            int delayedShipments,
            int exceptionShipments,
            int unassignedShipments,
            int dueToday,
            BigDecimal onTimeRatePercent,
            BigDecimal completionRatePercent
    ) {}

    public record FinancialKpi(
            String currency,
            boolean mixedCurrencies,
            BigDecimal billed,
            BigDecimal collected,
            BigDecimal receivables,
            BigDecimal operatingCost,
            BigDecimal grossMargin,
            BigDecimal grossMarginPercent
    ) {}

    public record FleetKpi(
            int totalVehicles,
            int availableVehicles,
            int onTripVehicles,
            int maintenanceVehicles,
            BigDecimal vehicleUtilizationPercent,
            int totalDrivers,
            int availableDrivers,
            int onTripDrivers,
            BigDecimal driverUtilizationPercent
    ) {}

    public record ModeMetric(String mode, int shipments, BigDecimal sharePercent) {}
    public record StatusMetric(String status, int shipments, BigDecimal sharePercent) {}

    public record TrendPoint(
            LocalDate date,
            int shipments,
            BigDecimal revenue,
            BigDecimal operatingCost
    ) {}

    public record LaneMetric(String lane, int shipments, int delayedShipments) {}

    public record DashboardException(
            String severity,
            String type,
            String reference,
            String message,
            String lane,
            String mode
    ) {}

    public record DashboardAction(
            String priority,
            String title,
            String detail,
            String href
    ) {}

    public record OperatingKpis(
            int activeShipments,
            BigDecimal revenueInvoiced,
            BigDecimal outstanding,
            BigDecimal grossProfit,
            BigDecimal overdueReceivables,
            long openQuotations,
            BigDecimal dueNext30Days,
            BigDecimal overallProfitMargin,
            long openTasks,
            long overdueTasks,
            long wonQuotations,
            BigDecimal salesWinRate
    ) {}

    public record MonthlyFinancialPoint(
            String month,
            BigDecimal revenue,
            BigDecimal grossProfit
    ) {}

    public record AgingMetric(
            String bucket,
            BigDecimal balance
    ) {}

    public record QuotationStatusMetric(
            String status,
            long count
    ) {}
}
