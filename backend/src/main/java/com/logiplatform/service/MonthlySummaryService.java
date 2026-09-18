package com.logiplatform.service;

import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Service
public class MonthlySummaryService {

    private final ShipmentRepository shipments;

    public MonthlySummaryService(ShipmentRepository shipments) {
        this.shipments = shipments;
    }

    public Summary summary(YearMonth month) {
        if (month == null) {
            throw new IllegalArgumentException("Month is required");
        }

        var tenant = TenantContext.getTenantId();
        if (tenant == null) {
            throw new IllegalStateException("No tenant context is available");
        }

        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);

        List<Shipment> rows =
                shipments.findAllByTenantIdAndDateOpenedGreaterThanEqualAndDateOpenedLessThan(
                        tenant, from, to);

        BigDecimal billed = sum(rows, Shipment::getAmountBilledToClient);
        BigDecimal grossProfit = sum(rows, Shipment::getGrossProfit);

        BigDecimal profitMargin = billed.signum() == 0
                ? BigDecimal.ZERO
                : grossProfit.multiply(BigDecimal.valueOf(100))
                    .divide(billed, 4, RoundingMode.HALF_UP);

        return new Summary(
                month.getMonth().toString(),
                rows.size(),
                sum(rows, Shipment::getGrossWeightKg),
                sum(rows, Shipment::getChargeableWeightKg),
                billed,
                sum(rows, Shipment::getAmountPaidByClient),
                sum(rows, Shipment::getAmountRemaining),
                sum(rows, Shipment::getAmountPaidToSupply),
                sum(rows, Shipment::getOtherExpenses),
                sum(rows, Shipment::getNetIncome),
                profitMargin,
                countStatus(rows, "COMPLETED"),
                countStatus(rows, "DEPARTED")
        );
    }

    private static BigDecimal sum(List<Shipment> rows, Function<Shipment, BigDecimal> getter) {
        return rows.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static long countStatus(List<Shipment> rows, String status) {
        return rows.stream()
                .filter(s -> s.getStatus() != null)
                .filter(s -> status.equalsIgnoreCase(s.getStatus().name()))
                .count();
    }

    public record Summary(
            String month,
            int shipments,
            BigDecimal grossWeightKg,
            BigDecimal chargeableWeightKg,
            BigDecimal billed,
            BigDecimal collected,
            BigDecimal remaining,
            BigDecimal supplierPayments,
            BigDecimal otherExpenses,
            BigDecimal netIncome,
            BigDecimal profitMargin,
            long completed,
            long departed
    ) {}
}
