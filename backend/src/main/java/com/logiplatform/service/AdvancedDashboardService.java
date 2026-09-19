package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.logiplatform.dto.AdvancedDashboardDtos.*;

/**
 * Read-only control-tower aggregation for the current tenant.
 *
 * The service deliberately uses SQL aggregation rather than loading the full
 * shipment table into memory. Every query is tenant-scoped and uses the
 * reporting JdbcTemplate backed by the tenant-aware datasource/RLS boundary.
 */
@Service
public class AdvancedDashboardService {

    private final JdbcTemplate jdbc;

    public AdvancedDashboardService(
            @Qualifier("reportingJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Response dashboard(LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not available");
        }

        return new Response(
                date,
                operations(tenantId, date),
                financial(tenantId),
                fleet(tenantId),
                modeMix(tenantId),
                statusMix(tenantId),
                trend(tenantId, date),
                topLanes(tenantId, date),
                exceptions(tenantId, date),
                actions(tenantId, date),
                operatingKpis(tenantId, date),
                monthlyFinancial(tenantId, date),
                receivablesAging(tenantId, date),
                quotationStatus(tenantId));
    }

    private OperationsKpi operations(UUID tenantId, LocalDate asOf) {
        return jdbc.queryForObject(
                """
                        SELECT
                            COUNT(*) AS total_shipments,
                            COUNT(*) FILTER (WHERE status NOT IN ('DELIVERED','COMPLETED','CANCELLED')) AS active_shipments,
                            COUNT(*) FILTER (WHERE status IN ('DELIVERED','COMPLETED')) AS delivered_shipments,
                            COUNT(*) FILTER (
                                WHERE eta IS NOT NULL
                                  AND eta < (?::date + INTERVAL '1 day')
                                  AND status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                            ) AS delayed_shipments,
                            COUNT(*) FILTER (
                                WHERE status IN ('ON_HOLD','CANCELLED')
                                   OR (eta IS NOT NULL AND eta < (?::date + INTERVAL '1 day')
                                       AND status NOT IN ('DELIVERED','COMPLETED','CANCELLED'))
                            ) AS exception_shipments,
                            COUNT(*) FILTER (
                                WHERE status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM trip_shipments ts
                                      JOIN trips t ON t.id = ts.trip_id AND t.tenant_id = ts.tenant_id
                                      WHERE ts.tenant_id = s.tenant_id
                                        AND ts.shipment_id = s.id
                                        AND t.status IN ('PLANNED','IN_PROGRESS')
                                  )
                            ) AS unassigned_shipments,
                            COUNT(*) FILTER (WHERE next_action_date = ?::date) AS due_today,
                            COALESCE(
                                ROUND(100.0 * COUNT(*) FILTER (
                                    WHERE status IN ('DELIVERED','COMPLETED')
                                      AND eta IS NOT NULL
                                      AND COALESCE(
                                          (SELECT MIN(e.occurred_at)
                                           FROM shipment_tracking_events e
                                           WHERE e.tenant_id = s.tenant_id
                                             AND e.shipment_id = s.id
                                             AND e.event_type = 'DELIVERED'),
                                          updated_at
                                      ) <= eta
                                ) / NULLIF(COUNT(*) FILTER (
                                    WHERE status IN ('DELIVERED','COMPLETED') AND eta IS NOT NULL
                                ),0), 2), 0
                            ) AS on_time_rate,
                            COALESCE(
                                ROUND(100.0 * COUNT(*) FILTER (WHERE status IN ('DELIVERED','COMPLETED'))
                                    / NULLIF(COUNT(*),0), 2), 0
                            ) AS completion_rate
                        FROM shipments s
                        WHERE tenant_id = ?
                        """,
                (rs, rowNum) -> new OperationsKpi(
                        rs.getInt("total_shipments"),
                        rs.getInt("active_shipments"),
                        rs.getInt("delivered_shipments"),
                        rs.getInt("delayed_shipments"),
                        rs.getInt("exception_shipments"),
                        rs.getInt("unassigned_shipments"),
                        rs.getInt("due_today"),
                        rs.getBigDecimal("on_time_rate"),
                        rs.getBigDecimal("completion_rate")),
                asOf, asOf, asOf, tenantId);
    }

    private FinancialKpi financial(UUID tenantId) {
        String displayCurrency = jdbc.queryForObject(
                "SELECT COALESCE((SELECT default_currency FROM tenant_profiles WHERE tenant_id = ?), 'USD')",
                String.class,
                tenantId);
        if (displayCurrency == null || displayCurrency.isBlank()) {
            displayCurrency = "USD";
        }
        displayCurrency = displayCurrency.trim().toUpperCase();

        Integer currencyCount = jdbc.queryForObject(
                """
                        SELECT COUNT(DISTINCT COALESCE(NULLIF(UPPER(currency),''), ?))
                        FROM shipments
                        WHERE tenant_id = ?
                        """,
                Integer.class,
                displayCurrency, tenantId);

        BigDecimal[] totals = jdbc.queryForObject(
                """
                        SELECT
                            COALESCE(SUM(COALESCE(amount_billed_to_client, 0)),0) AS billed,
                            COALESCE(SUM(COALESCE(amount_paid_by_client,0)),0) AS collected,
                            COALESCE(SUM(GREATEST(COALESCE(amount_billed_to_client, 0) - COALESCE(amount_paid_by_client,0),0)),0) AS receivables,
                            COALESCE(SUM(COALESCE(supplier_cost,0) + COALESCE(other_cost,0)),0) AS gross_cost,
                            COALESCE(SUM(COALESCE(supplier_cost,0) + COALESCE(other_cost,0) + COALESCE(other_expenses,0)),0) AS operating_cost
                        FROM shipments
                        WHERE tenant_id = ?
                          AND COALESCE(NULLIF(UPPER(currency),''), ?) = ?
                        """,
                (rs, rowNum) -> new BigDecimal[] {
                        rs.getBigDecimal("billed"),
                        rs.getBigDecimal("collected"),
                        rs.getBigDecimal("receivables"),
                        rs.getBigDecimal("gross_cost"),
                        rs.getBigDecimal("operating_cost")
                },
                tenantId, displayCurrency, displayCurrency);

        BigDecimal billed = nz(totals[0]);
        BigDecimal collected = nz(totals[1]);
        BigDecimal receivables = nz(totals[2]);
        BigDecimal grossCost = nz(totals[3]);
        BigDecimal operatingCost = nz(totals[4]);
        // Gross margin follows the AAL workbook rule: revenue minus supplier/other cost.
        // Other expenses remain in operating cost and the separate net-income view.
        BigDecimal grossMargin = billed.subtract(grossCost);
        BigDecimal marginPercent = billed.signum() == 0
                ? BigDecimal.ZERO
                : grossMargin.multiply(BigDecimal.valueOf(100))
                        .divide(billed, 2, java.math.RoundingMode.HALF_UP);

        return new FinancialKpi(
                displayCurrency,
                currencyCount != null && currencyCount > 1,
                billed,
                collected,
                receivables,
                operatingCost,
                grossMargin,
                marginPercent);
    }

    private FleetKpi fleet(UUID tenantId) {
        return jdbc.queryForObject(
                """
                        SELECT
                            (SELECT COUNT(*) FROM vehicles WHERE tenant_id = ?) AS total_vehicles,
                            (SELECT COUNT(*) FROM vehicles WHERE tenant_id = ? AND status = 'AVAILABLE') AS available_vehicles,
                            (SELECT COUNT(*) FROM vehicles WHERE tenant_id = ? AND status = 'ON_TRIP') AS on_trip_vehicles,
                            (SELECT COUNT(*) FROM vehicles WHERE tenant_id = ? AND status = 'MAINTENANCE') AS maintenance_vehicles,
                            (SELECT COUNT(*) FROM drivers WHERE tenant_id = ?) AS total_drivers,
                            (SELECT COUNT(*) FROM drivers WHERE tenant_id = ? AND status = 'AVAILABLE') AS available_drivers,
                            (SELECT COUNT(*) FROM drivers WHERE tenant_id = ? AND status = 'ON_TRIP') AS on_trip_drivers
                        """,
                (rs, rowNum) -> {
                    int totalVehicles = rs.getInt("total_vehicles");
                    int totalDrivers = rs.getInt("total_drivers");
                    int availableVehicles = rs.getInt("available_vehicles");
                    int availableDrivers = rs.getInt("available_drivers");
                    return new FleetKpi(
                            totalVehicles,
                            availableVehicles,
                            rs.getInt("on_trip_vehicles"),
                            rs.getInt("maintenance_vehicles"),
                            totalVehicles == 0 ? BigDecimal.ZERO : percent(availableVehicles, totalVehicles),
                            totalDrivers,
                            availableDrivers,
                            rs.getInt("on_trip_drivers"),
                            totalDrivers == 0 ? BigDecimal.ZERO : percent(availableDrivers, totalDrivers));
                },
                tenantId, tenantId, tenantId, tenantId,
                tenantId, tenantId, tenantId);
    }

    private List<ModeMetric> modeMix(UUID tenantId) {
        List<ModeMetric> rows = jdbc.query(
                """
                        SELECT transport_mode, COUNT(*) AS shipments
                        FROM shipments
                        WHERE tenant_id = ?
                        GROUP BY transport_mode
                        ORDER BY shipments DESC, transport_mode
                        """,
                (rs, rowNum) -> new ModeMetric(
                        rs.getString("transport_mode"),
                        rs.getInt("shipments"),
                        BigDecimal.ZERO),
                tenantId);
        int total = rows.stream().mapToInt(ModeMetric::shipments).sum();
        return rows.stream()
                .map(x -> new ModeMetric(x.mode(), x.shipments(),
                        total == 0 ? BigDecimal.ZERO : percent(x.shipments(), total)))
                .toList();
    }

    private List<StatusMetric> statusMix(UUID tenantId) {
        List<StatusMetric> rows = jdbc.query(
                """
                        SELECT status, COUNT(*) AS shipments
                        FROM shipments
                        WHERE tenant_id = ?
                        GROUP BY status
                        ORDER BY shipments DESC, status
                        """,
                (rs, rowNum) -> new StatusMetric(
                        rs.getString("status"),
                        rs.getInt("shipments"),
                        BigDecimal.ZERO),
                tenantId);
        int total = rows.stream().mapToInt(StatusMetric::shipments).sum();
        return rows.stream()
                .map(x -> new StatusMetric(x.status(), x.shipments(),
                        total == 0 ? BigDecimal.ZERO : percent(x.shipments(), total)))
                .toList();
    }

    private List<TrendPoint> trend(UUID tenantId, LocalDate asOf) {
        return jdbc.query(
                """
                        SELECT d::date AS day,
                               COUNT(s.id) AS shipments,
                               COALESCE(SUM(COALESCE(s.amount_billed_to_client, 0)),0) AS revenue,
                               COALESCE(SUM(COALESCE(s.supplier_cost,0) + COALESCE(s.other_cost,0) + COALESCE(s.other_expenses,0)),0) AS operating_cost
                        FROM generate_series(?::date - INTERVAL '6 days', ?::date, INTERVAL '1 day') d
                        LEFT JOIN shipments s
                          ON s.tenant_id = ?
                         AND COALESCE(s.date_opened, s.created_at::date) = d::date
                         AND COALESCE(NULLIF(UPPER(s.currency),''),
                                      (SELECT COALESCE(default_currency,'USD') FROM tenant_profiles WHERE tenant_id=?))
                             = (SELECT COALESCE(default_currency,'USD') FROM tenant_profiles WHERE tenant_id=?)
                        GROUP BY d::date
                        ORDER BY d::date
                        """,
                (rs, rowNum) -> new TrendPoint(
                        rs.getDate("day").toLocalDate(),
                        rs.getInt("shipments"),
                        nz(rs.getBigDecimal("revenue")),
                        nz(rs.getBigDecimal("operating_cost"))),
                asOf, asOf, tenantId, tenantId, tenantId);
    }

    private List<LaneMetric> topLanes(UUID tenantId, LocalDate asOf) {
        return jdbc.query(
                """
                        SELECT
                            COALESCE(NULLIF(TRIM(origin_city_port),''), NULLIF(TRIM(origin_address),''), 'Unknown')
                            || ' → ' ||
                            COALESCE(NULLIF(TRIM(destination_city_port),''), NULLIF(TRIM(destination_address),''), 'Unknown') AS lane,
                            COUNT(*) AS shipments,
                            COUNT(*) FILTER (
                                WHERE eta IS NOT NULL AND eta < (?::date + INTERVAL '1 day')
                                  AND status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                            ) AS delayed_shipments
                        FROM shipments
                        WHERE tenant_id = ?
                        GROUP BY lane
                        ORDER BY shipments DESC, lane
                        LIMIT 10
                        """,
                (rs, rowNum) -> new LaneMetric(
                        rs.getString("lane"),
                        rs.getInt("shipments"),
                        rs.getInt("delayed_shipments")),
                asOf, tenantId);
    }

    private List<DashboardException> exceptions(UUID tenantId, LocalDate asOf) {
        return jdbc.query(
                """
                        SELECT severity, exception_type AS type, reference, message, lane, mode
                        FROM (
                            SELECT 'HIGH' AS severity,
                                   'DELAYED_SHIPMENT' AS exception_type,
                                   s.reference_code AS reference,
                                   'ETA has passed without delivery' AS message,
                                   COALESCE(NULLIF(TRIM(s.origin_city_port),''), s.origin_address, 'Unknown') || ' → ' ||
                                   COALESCE(NULLIF(TRIM(s.destination_city_port),''), s.destination_address, 'Unknown') AS lane,
                                   s.transport_mode AS mode,
                                   1 AS priority
                            FROM shipments s
                            WHERE s.tenant_id = ?
                              AND s.eta IS NOT NULL
                              AND s.eta < (?::date + INTERVAL '1 day')
                              AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                            UNION ALL
                            SELECT CASE e.severity WHEN 'CRITICAL' THEN 'CRITICAL' WHEN 'HIGH' THEN 'HIGH' ELSE e.severity END,
                                   e.exception_type,
                                   s.reference_code,
                                   COALESCE(e.title, e.description, 'Open operational exception'),
                                   COALESCE(NULLIF(TRIM(s.origin_city_port),''), s.origin_address, 'Unknown') || ' → ' ||
                                   COALESCE(NULLIF(TRIM(s.destination_city_port),''), s.destination_address, 'Unknown'),
                                   s.transport_mode,
                                   0
                            FROM logistics_exceptions e
                            JOIN shipments s ON s.id = e.shipment_id AND s.tenant_id = e.tenant_id
                            WHERE e.tenant_id = ? AND e.status = 'OPEN'
                            UNION ALL
                            SELECT 'MEDIUM',
                                   'UNASSIGNED_SHIPMENT',
                                   s.reference_code,
                                   'Active shipment has no planned or in-progress route',
                                   COALESCE(NULLIF(TRIM(s.origin_city_port),''), s.origin_address, 'Unknown') || ' → ' ||
                                   COALESCE(NULLIF(TRIM(s.destination_city_port),''), s.destination_address, 'Unknown'),
                                   s.transport_mode,
                                   2
                            FROM shipments s
                            WHERE s.tenant_id = ?
                              AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                              AND NOT EXISTS (
                                  SELECT 1 FROM trip_shipments ts
                                  JOIN trips t ON t.id=ts.trip_id AND t.tenant_id=ts.tenant_id
                                  WHERE ts.tenant_id=s.tenant_id AND ts.shipment_id=s.id
                                    AND t.status IN ('PLANNED','IN_PROGRESS')
                              )
                        ) x
                        ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                                 priority, reference
                        LIMIT 25
                        """,
                (rs, rowNum) -> new DashboardException(
                        rs.getString("severity"),
                        rs.getString("type"),
                        rs.getString("reference"),
                        rs.getString("message"),
                        rs.getString("lane"),
                        rs.getString("mode")),
                tenantId, asOf, tenantId, tenantId);
    }

    private List<DashboardAction> actions(UUID tenantId, LocalDate asOf) {
        int delayed = scalarInt("""
                SELECT COUNT(*) FROM shipments
                WHERE tenant_id=? AND eta IS NOT NULL AND eta < (?::date + INTERVAL '1 day')
                  AND status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                """, tenantId, asOf);
        int unassigned = scalarInt("""
                SELECT COUNT(*) FROM shipments s
                WHERE s.tenant_id=? AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')
                  AND NOT EXISTS (
                    SELECT 1 FROM trip_shipments ts JOIN trips t ON t.id=ts.trip_id AND t.tenant_id=ts.tenant_id
                    WHERE ts.tenant_id=s.tenant_id AND ts.shipment_id=s.id AND t.status IN ('PLANNED','IN_PROGRESS')
                  )
                """, tenantId);
        int openExceptions = scalarInt("SELECT COUNT(*) FROM logistics_exceptions WHERE tenant_id=? AND status='OPEN'",
                tenantId);
        int overdueTasks = scalarInt(
                "SELECT COUNT(*) FROM task_records WHERE tenant_id=? AND UPPER(COALESCE(status,'')) NOT IN ('COMPLETED','CANCELLED') AND due_date < ?::date",
                tenantId, asOf);

        java.util.ArrayList<DashboardAction> out = new java.util.ArrayList<>();
        if (delayed > 0)
            out.add(new DashboardAction("URGENT", "Recover delayed shipments",
                    delayed + " shipment(s) have passed ETA.", "/shipments"));
        if (openExceptions > 0)
            out.add(new DashboardAction("HIGH", "Clear open exceptions",
                    openExceptions + " operational exception(s) remain open.", "/shipments"));
        if (unassigned > 0)
            out.add(new DashboardAction("HIGH", "Assign unplanned jobs",
                    unassigned + " active shipment(s) have no route assignment.", "/trips"));
        if (overdueTasks > 0)
            out.add(new DashboardAction("MEDIUM", "Review overdue tasks", overdueTasks + " task(s) are overdue.",
                    "/command-center"));
        if (out.isEmpty())
            out.add(new DashboardAction("LOW", "Operations stable",
                    "No priority action was detected for the selected date.", "/shipments"));
        return out.stream().limit(8).toList();
    }

    private int scalarInt(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static BigDecimal percent(int value, int total) {
        return BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
    }

    private OperatingKpis operatingKpis(UUID tenantId, LocalDate asOf) {
        String currency = reportingCurrency(tenantId);

        return jdbc.queryForObject(
                """
                        SELECT
                            (SELECT COUNT(*)
                               FROM shipments s
                              WHERE s.tenant_id = ?
                                AND s.status NOT IN ('DELIVERED','COMPLETED','CANCELLED')) AS active_shipments,

                            (SELECT COALESCE(SUM(i.invoice_amount),0)
                               FROM commercial_invoices i
                              WHERE i.tenant_id = ?
                                AND UPPER(i.currency) = ?) AS revenue_invoiced,

                            (SELECT COALESCE(SUM(GREATEST(i.invoice_amount - i.amount_paid,0)),0)
                               FROM commercial_invoices i
                              WHERE i.tenant_id = ?
                                AND UPPER(i.currency) = ?) AS outstanding,

                            (SELECT COALESCE(SUM(
                                      COALESCE(s.amount_billed_to_client,0)
                                      - COALESCE(s.supplier_cost,0)
                                      - COALESCE(s.other_cost,0)
                                   ),0)
                               FROM shipments s
                              WHERE s.tenant_id = ?
                                AND UPPER(COALESCE(NULLIF(s.currency,''),?)) = ?) AS gross_profit,

                            (SELECT COALESCE(SUM(GREATEST(i.invoice_amount - i.amount_paid,0)),0)
                               FROM commercial_invoices i
                              WHERE i.tenant_id = ?
                                AND UPPER(i.currency) = ?
                                AND i.due_date < ?) AS overdue_receivables,

                            (SELECT COUNT(*)
                               FROM commercial_quotes q
                              WHERE q.tenant_id = ?
                                AND UPPER(COALESCE(q.status,'')) NOT IN ('WON','LOST','EXPIRED')) AS open_quotations,

                            (SELECT COALESCE(SUM(GREATEST(i.invoice_amount - i.amount_paid,0)),0)
                               FROM commercial_invoices i
                              WHERE i.tenant_id = ?
                                AND UPPER(i.currency) = ?
                                AND i.due_date >= ?
                                AND i.due_date <= ?) AS due_next_30_days,

                            (SELECT COUNT(*)
                               FROM task_records t
                              WHERE t.tenant_id = ?
                                AND UPPER(COALESCE(t.status,'')) NOT IN ('COMPLETED','CANCELLED')) AS open_tasks,

                            (SELECT COUNT(*)
                               FROM task_records t
                              WHERE t.tenant_id = ?
                                AND UPPER(COALESCE(t.status,'')) NOT IN ('COMPLETED','CANCELLED')
                                AND t.due_date < ?) AS overdue_tasks,

                            (SELECT COUNT(*)
                               FROM commercial_quotes q
                              WHERE q.tenant_id = ?
                                AND UPPER(COALESCE(q.status,'')) = 'WON') AS won_quotations,

                            (SELECT COUNT(*)
                               FROM commercial_quotes q
                              WHERE q.tenant_id = ?
                                AND UPPER(COALESCE(q.status,'')) IN ('WON','LOST')) AS decided_quotations
                        """,
                (rs, rowNum) -> {
                    BigDecimal invoiced = nz(rs.getBigDecimal("revenue_invoiced"));
                    BigDecimal grossProfit = nz(rs.getBigDecimal("gross_profit"));
                    long won = rs.getLong("won_quotations");
                    long decided = rs.getLong("decided_quotations");

                    BigDecimal margin = invoiced.signum() == 0
                            ? BigDecimal.ZERO
                            : grossProfit.multiply(BigDecimal.valueOf(100))
                                    .divide(invoiced, 4, java.math.RoundingMode.HALF_UP);

                    BigDecimal winRate = decided == 0
                            ? BigDecimal.ZERO
                            : BigDecimal.valueOf(won).multiply(BigDecimal.valueOf(100))
                                    .divide(BigDecimal.valueOf(decided), 4, java.math.RoundingMode.HALF_UP);

                    return new OperatingKpis(
                            rs.getInt("active_shipments"),
                            invoiced,
                            nz(rs.getBigDecimal("outstanding")),
                            grossProfit,
                            nz(rs.getBigDecimal("overdue_receivables")),
                            rs.getLong("open_quotations"),
                            nz(rs.getBigDecimal("due_next_30_days")),
                            margin,
                            rs.getLong("open_tasks"),
                            rs.getLong("overdue_tasks"),
                            won,
                            winRate);
                },
                tenantId,
                tenantId, currency,
                tenantId, currency,
                tenantId, currency, currency,
                tenantId, currency, asOf,
                tenantId,
                tenantId, currency, asOf, asOf.plusDays(30),
                tenantId,
                tenantId, asOf,
                tenantId,
                tenantId);
    }

    private List<MonthlyFinancialPoint> monthlyFinancial(UUID tenantId, LocalDate asOf) {
        String currency = reportingCurrency(tenantId);
        LocalDate firstMonth = asOf.withDayOfMonth(1).minusMonths(11);

        return jdbc.query(
                """
                        SELECT
                            TO_CHAR(d, 'YYYY-MM') AS month,
                            COALESCE(SUM(
                                CASE WHEN UPPER(COALESCE(NULLIF(s.currency,''),?)) = ?
                                THEN COALESCE(s.amount_billed_to_client,0) ELSE 0 END
                            ),0) AS revenue,
                            COALESCE(SUM(
                                CASE WHEN UPPER(COALESCE(NULLIF(s.currency,''),?)) = ?
                                THEN COALESCE(s.amount_billed_to_client,0)
                                     - COALESCE(s.supplier_cost,0)
                                     - COALESCE(s.other_cost,0)
                                ELSE 0 END
                            ),0) AS gross_profit
                        FROM generate_series(?::date, ?::date, INTERVAL '1 month') d
                        LEFT JOIN shipments s
                          ON s.tenant_id = ?
                         AND s.etd >= d
                         AND s.etd < d + INTERVAL '1 month'
                        GROUP BY d
                        ORDER BY d
                        """,
                (rs, rowNum) -> new MonthlyFinancialPoint(
                        rs.getString("month"),
                        nz(rs.getBigDecimal("revenue")),
                        nz(rs.getBigDecimal("gross_profit"))),
                currency, currency, currency, currency,
                firstMonth, asOf.withDayOfMonth(1),
                tenantId);
    }

    private List<AgingMetric> receivablesAging(UUID tenantId, LocalDate asOf) {
        String currency = reportingCurrency(tenantId);

        return jdbc.query(
                """
                        WITH balances AS (
                            SELECT
                                GREATEST(i.invoice_amount - i.amount_paid,0) AS balance,
                                i.due_date
                            FROM commercial_invoices i
                            WHERE i.tenant_id = ?
                              AND UPPER(i.currency) = ?
                              AND GREATEST(i.invoice_amount - i.amount_paid,0) > 0
                        )
                        SELECT bucket, COALESCE(SUM(balance),0) AS balance
                        FROM (
                            SELECT
                                CASE
                                    WHEN due_date IS NULL OR due_date >= ? THEN 'Current'
                                    WHEN (? - due_date) <= 30 THEN '1-30 Days'
                                    WHEN (? - due_date) <= 60 THEN '31-60 Days'
                                    WHEN (? - due_date) <= 90 THEN '61-90 Days'
                                    ELSE '90+ Days'
                                END AS bucket,
                                balance
                            FROM balances
                        ) x
                        GROUP BY bucket
                        ORDER BY CASE bucket
                            WHEN 'Current' THEN 0
                            WHEN '1-30 Days' THEN 1
                            WHEN '31-60 Days' THEN 2
                            WHEN '61-90 Days' THEN 3
                            ELSE 4
                        END
                        """,
                (rs, rowNum) -> new AgingMetric(
                        rs.getString("bucket"),
                        nz(rs.getBigDecimal("balance"))),
                tenantId, currency, asOf, asOf, asOf, asOf);
    }

    private List<QuotationStatusMetric> quotationStatus(UUID tenantId) {
        return jdbc.query(
                """
                        SELECT status, COUNT(*) AS count
                        FROM commercial_quotes
                        WHERE tenant_id = ?
                        GROUP BY status
                        ORDER BY status
                        """,
                (rs, rowNum) -> new QuotationStatusMetric(
                        rs.getString("status"),
                        rs.getLong("count")),
                tenantId);
    }

    private String reportingCurrency(UUID tenantId) {
        String value = jdbc.queryForObject(
                "SELECT COALESCE((SELECT default_currency FROM tenant_profiles WHERE tenant_id = ?), 'USD')",
                String.class,
                tenantId);
        return value == null || value.isBlank() ? "USD" : value.trim().toUpperCase();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
