package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AalBusinessDtos {
    private AalBusinessDtos() {}

    public record ShipmentFinancial(
            UUID shipmentId,
            String reference,
            String currency,
            BigDecimal grossWeightKg,
            BigDecimal volumetricWeightKg,
            BigDecimal chargeableWeightKg,
            BigDecimal supplierCost,
            BigDecimal otherCost,
            BigDecimal totalCost,
            BigDecimal billed,
            BigDecimal collected,
            BigDecimal receivable,
            BigDecimal supplierPaid,
            BigDecimal otherExpenses,
            BigDecimal grossProfit,
            BigDecimal marginPercent,
            BigDecimal netIncome,
            String paymentStatus) {}

    public record Cockpit(
            LocalDate from,
            LocalDate to,
            String currency,
            long shipments,
            long activeShipments,
            long deliveredShipments,
            BigDecimal grossWeightKg,
            BigDecimal chargeableWeightKg,
            BigDecimal billed,
            BigDecimal collected,
            BigDecimal receivable,
            BigDecimal totalCost,
            BigDecimal grossProfit,
            BigDecimal marginPercent,
            BigDecimal netIncome,
            long openTasks,
            long overdueTasks,
            long openQuotes,
            long wonQuotes,
            List<Monthly> monthly,
            List<Lane> lanes) {}

    public record Monthly(
            String month,
            long shipments,
            BigDecimal revenue,
            BigDecimal collected,
            BigDecimal receivable,
            BigDecimal cost,
            BigDecimal grossProfit) {}

    public record Lane(
            String origin,
            String destination,
            long shipments,
            BigDecimal revenue,
            BigDecimal grossProfit,
            long delayed) {}
}
