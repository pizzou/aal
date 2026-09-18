
package com.logiplatform.service;

import com.logiplatform.dto.ReportingDtos.*;
import com.logiplatform.tenancy.TenantContext;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 
 * Canonical detailed management reporting service.
 *
 * Source-of-truth boundaries:
 *
 * Operations
 * -> shipments / trips / operational exceptions / tasks
 *
 * Revenue + receivables
 * -> commercial_invoices / commercial_payments
 *
 * Shipment profitability
 * -> shipment revenue + supplier/operational cost fields
 *
 * Sales pipeline
 * -> commercial_quotes
 *
 * General expenses
 * -> expense_records
 *
 * All queries are explicitly tenant scoped.
 */
@Service
public class ReportingService {

    private final JdbcTemplate jdbc;

    public ReportingService(
            @Qualifier("reportingJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /*
     * 
     * ========================================================================
     * CANONICAL MANAGEMENT REPORT
     * ========================================================================
     */

    public ManagementReport managementReport(
            LocalDate from,
            LocalDate to) {
        UUID tenantId = requireTenant();

        LocalDate end = to == null
                ? LocalDate.now()
                : to;

        LocalDate start = from == null
                ? end.minusDays(29)
                : from;

        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "Report start date cannot be after end date");
        }

        String currency = reportingCurrency(tenantId);

        boolean mixedCurrencies = hasMixedCurrencies(
                tenantId,
                currency);

        return new ManagementReport(
                start,
                end,
                currency,
                mixedCurrencies,
                operations(
                        tenantId,
                        start,
                        end),
                financial(
                        tenantId,
                        start,
                        end,
                        currency),
                receivables(
                        tenantId,
                        start,
                        end,
                        currency),
                salesPipeline(
                        tenantId,
                        start,
                        end,
                        currency),
                tasks(
                        tenantId,
                        start,
                        end),
                fleet(tenantId),
                customerProfitability(
                        tenantId,
                        start,
                        end,
                        currency),
                carrierProfitability(
                        tenantId,
                        start,
                        end,
                        currency),
                shipmentProfitability(
                        tenantId,
                        start,
                        end,
                        currency),
                monthlyTrend(
                        tenantId,
                        start,
                        end,
                        currency),
                receivablesAging(
                        tenantId,
                        end,
                        currency),
                quotationPipeline(
                        tenantId,
                        start,
                        end,
                        currency),
                operationalExceptions(
                        tenantId,
                        end));

    }

    /*
     * 
     * ========================================================================
     * OPERATIONS
     * ========================================================================
     */

    private OperationsSummary operations(
            UUID tenantId,
            LocalDate from,
            LocalDate to) {
        return jdbc.queryForObject(
                """
                        SELECT
                        COUNT(*) AS total_shipments,


                                     COUNT(*) FILTER (
                                         WHERE status NOT IN
                                             ('DELIVERED','COMPLETED','CANCELLED')
                                     ) AS active_shipments,

                                     COUNT(*) FILTER (
                                         WHERE status IN
                                             ('DELIVERED','COMPLETED')
                                     ) AS completed_shipments,

                                     COUNT(*) FILTER (
                                         WHERE etd IS NOT NULL
                                           AND etd >= ?::date
                                           AND etd < (?::date + INTERVAL '1 day')
                                     ) AS departed_shipments,

                                     COUNT(*) FILTER (
                                         WHERE eta IS NOT NULL
                                           AND eta >= ?::date
                                           AND eta < (?::date + INTERVAL '1 day')
                                           AND status <> 'CANCELLED'
                                     ) AS arrivals_today,

                                     COUNT(*) FILTER (
                                         WHERE eta IS NOT NULL
                                           AND eta < (?::date + INTERVAL '1 day')
                                           AND status NOT IN
                                               ('DELIVERED','COMPLETED','CANCELLED')
                                     ) AS delayed_shipments,

                                     COUNT(*) FILTER (
                                         WHERE status IN ('ON_HOLD','CANCELLED')
                                            OR (
                                                eta IS NOT NULL
                                                AND eta < (?::date + INTERVAL '1 day')
                                                AND status NOT IN
                                                    ('DELIVERED','COMPLETED','CANCELLED')
                                            )
                                     ) AS exception_shipments,

                                     COUNT(*) FILTER (
                                         WHERE status NOT IN
                                             ('DELIVERED','COMPLETED','CANCELLED')
                                           AND NOT EXISTS (
                                               SELECT 1
                                               FROM trip_shipments ts
                                               JOIN trips t
                                                 ON t.id = ts.trip_id
                                                AND t.tenant_id = ts.tenant_id
                                               WHERE ts.tenant_id = s.tenant_id
                                                 AND ts.shipment_id = s.id
                                                 AND t.status IN
                                                     ('PLANNED','IN_PROGRESS')
                                           )
                                     ) AS unassigned_shipments,

                                     COUNT(*) FILTER (
                                         WHERE status IN ('DELIVERED','COMPLETED')
                                           AND eta IS NOT NULL
                                           AND COALESCE(
                                               (
                                                   SELECT MIN(e.occurred_at)
                                                   FROM shipment_tracking_events e
                                                   WHERE e.tenant_id = s.tenant_id
                                                     AND e.shipment_id = s.id
                                                     AND e.event_type = 'DELIVERED'
                                               ),
                                               s.updated_at
                                           ) <= s.eta
                                     ) AS on_time_shipments,

                                     COUNT(*) FILTER (
                                         WHERE status IN ('DELIVERED','COMPLETED')
                                           AND eta IS NOT NULL
                                     ) AS measurable_completed,

                                     COUNT(*) FILTER (
                                         WHERE status IN ('DELIVERED','COMPLETED')
                                     ) AS completed_total

                                 FROM shipments s
                                 WHERE s.tenant_id = ?
                                   AND COALESCE(
                                       s.date_opened,
                                       s.created_at::date
                                   ) BETWEEN ?::date AND ?::date
                                 """,
                (rs, rowNum) -> {
                    long total = rs.getLong("total_shipments");

                    long completed = rs.getLong("completed_total");

                    long measurable = rs.getLong("measurable_completed");

                    long onTime = rs.getLong("on_time_shipments");

                    return new OperationsSummary(
                            total,
                            rs.getLong("active_shipments"),
                            completed,
                            rs.getLong("departed_shipments"),
                            rs.getLong("arrivals_today"),
                            rs.getLong("delayed_shipments"),
                            rs.getLong("exception_shipments"),
                            rs.getLong("unassigned_shipments"),
                            ratio(
                                    onTime,
                                    measurable),
                            ratio(
                                    completed,
                                    total));
                },
                to,
                to,
                to,
                to,
                to,
                to,
                tenantId,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * FINANCE
     * ========================================================================
     */

    private FinancialSummary financial(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        BigDecimal[] invoiceTotals = jdbc.queryForObject(
                """
                        SELECT
                        COALESCE(SUM(invoice_amount),0) AS invoiced,
                        COALESCE(SUM(amount_paid),0) AS collected,
                        COALESCE(
                        SUM(
                        GREATEST(
                        invoice_amount - amount_paid,
                        0
                        )
                        ),
                        0
                        ) AS outstanding,


                                             COALESCE(
                                                 SUM(
                                                     CASE
                                                         WHEN due_date IS NOT NULL
                                                          AND due_date < ?::date
                                                         THEN GREATEST(
                                                             invoice_amount - amount_paid,
                                                             0
                                                         )
                                                         ELSE 0
                                                     END
                                                 ),
                                                 0
                                             ) AS overdue

                                         FROM commercial_invoices
                                         WHERE tenant_id = ?
                                           AND UPPER(currency) = ?
                                           AND issue_date BETWEEN ?::date AND ?::date
                                         """,
                (rs, rowNum) -> new BigDecimal[] {
                        nz(
                                rs.getBigDecimal(
                                        "invoiced")),
                        nz(
                                rs.getBigDecimal(
                                        "collected")),
                        nz(
                                rs.getBigDecimal(
                                        "outstanding")),
                        nz(
                                rs.getBigDecimal(
                                        "overdue"))
                },
                to,
                tenantId,
                currency,
                from,
                to);

        BigDecimal[] shipmentCosts = jdbc.queryForObject(
                """
                        SELECT
                            COALESCE(
                                SUM(COALESCE(s.supplier_cost,0)),
                                0
                            ) AS supplier_cost,

                            COALESCE(
                                SUM(COALESCE(s.other_cost,0)),
                                0
                            ) AS other_cost,

                            COALESCE(
                                SUM(
                                    COALESCE(
                                        s.amount_billed_to_client,
                                        s.amount_billed_to_client,
                                        0
                                    )
                                    - COALESCE(s.supplier_cost,0)
                                    - COALESCE(s.other_cost,0)
                                ),
                                0
                            ) AS gross_profit

                        FROM shipments s
                        WHERE s.tenant_id = ?
                          AND UPPER(
                              COALESCE(
                                  NULLIF(s.currency,''),
                                  ?
                              )
                          ) = ?
                          AND COALESCE(
                              s.date_opened,
                              s.created_at::date
                          ) BETWEEN ?::date AND ?::date
                        """,
                (rs, rowNum) -> new BigDecimal[] {
                        nz(
                                rs.getBigDecimal(
                                        "supplier_cost")),
                        nz(
                                rs.getBigDecimal(
                                        "other_cost")),
                        nz(
                                rs.getBigDecimal(
                                        "gross_profit"))
                },
                tenantId,
                currency,
                currency,
                from,
                to);

        BigDecimal expenses = scalarMoney(
                """
                        SELECT COALESCE(
                            SUM(
                                COALESCE(
                                    usd_equivalent,
                                    original_amount
                                )
                            ),
                            0
                        )
                        FROM expense_records
                        WHERE tenant_id = ?
                          AND expense_date BETWEEN ?::date AND ?::date
                          AND UPPER(currency) = ?
                        """,
                tenantId,
                from,
                to,
                currency);

        BigDecimal invoiced = nz(invoiceTotals[0]);

        BigDecimal collected = nz(invoiceTotals[1]);

        BigDecimal outstanding = nz(invoiceTotals[2]);

        BigDecimal overdue = nz(invoiceTotals[3]);

        BigDecimal supplierCost = nz(shipmentCosts[0]);

        BigDecimal otherCost = nz(shipmentCosts[1]);

        BigDecimal grossProfit = nz(shipmentCosts[2]);

        BigDecimal netProfit = grossProfit.subtract(expenses);

        BigDecimal margin = invoiced.signum() == 0
                ? BigDecimal.ZERO
                : grossProfit
                        .multiply(BigDecimal.valueOf(100))
                        .divide(
                                invoiced,
                                2,
                                RoundingMode.HALF_UP);

        BigDecimal collectionRate = invoiced.signum() == 0
                ? BigDecimal.ZERO
                : collected
                        .multiply(BigDecimal.valueOf(100))
                        .divide(
                                invoiced,
                                2,
                                RoundingMode.HALF_UP);

        return new FinancialSummary(
                invoiced,
                collected,
                outstanding,
                overdue,
                supplierCost,
                otherCost,
                grossProfit,
                margin,
                expenses,
                netProfit,
                collectionRate);

    }

    /*
     * 
     * ========================================================================
     * RECEIVABLES
     * ========================================================================
     */

    private ReceivablesSummary receivables(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        return jdbc.queryForObject(
                """
                        SELECT
                        COUNT(*) AS invoice_count,


                                     COUNT(*) FILTER (
                                         WHERE amount_paid = 0
                                           AND invoice_amount > 0
                                     ) AS unpaid,

                                     COUNT(*) FILTER (
                                         WHERE amount_paid > 0
                                           AND amount_paid < invoice_amount
                                     ) AS partially_paid,

                                     COUNT(*) FILTER (
                                         WHERE due_date IS NOT NULL
                                           AND due_date < ?::date
                                           AND invoice_amount > amount_paid
                                     ) AS overdue_invoices,

                                     COALESCE(SUM(invoice_amount),0) AS invoiced,

                                     COALESCE(SUM(amount_paid),0) AS collected,

                                     COALESCE(
                                         SUM(
                                             GREATEST(
                                                 invoice_amount - amount_paid,
                                                 0
                                             )
                                         ),
                                         0
                                     ) AS outstanding,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN due_date IS NOT NULL
                                                  AND due_date < ?::date
                                                 THEN GREATEST(
                                                     invoice_amount - amount_paid,
                                                     0
                                                 )
                                                 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS overdue,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN due_date = ?::date
                                                 THEN GREATEST(invoice_amount - amount_paid, 0)
                                                 ELSE 0
                                             END), 0) AS due_today,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN due_date >= ?::date
                                                  AND due_date <= (?::date + 30)
                                                 THEN GREATEST(
                                                     invoice_amount - amount_paid,
                                                     0
                                                 )
                                                 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS due_next_30

                                 FROM commercial_invoices
                                 WHERE tenant_id = ?
                                   AND UPPER(currency) = ?
                                   AND issue_date <= ?::date
                                 """,
                (rs, rowNum) -> new ReceivablesSummary(
                        rs.getLong("invoice_count"),
                        rs.getLong("unpaid"),
                        rs.getLong("partially_paid"),
                        rs.getLong("overdue_invoices"),
                        nz(
                                rs.getBigDecimal("invoiced")),
                        nz(
                                rs.getBigDecimal("collected")),
                        nz(
                                rs.getBigDecimal("outstanding")),
                        nz(
                                rs.getBigDecimal("overdue")),
                        nz(
                                rs.getBigDecimal("due_next_30")),
                        nz(
                                rs.getBigDecimal("due_today"))),
                to,
                to,
                to,
                to,
                to,
                tenantId,
                currency,
                to);

    }

    /*
     * 
     * ========================================================================
     * SALES PIPELINE
     * ========================================================================
     */

    private SalesPipelineSummary salesPipeline(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        return jdbc.queryForObject(
                """
                        SELECT
                        COUNT(*) AS total_quotes,


                                     COUNT(*) FILTER (
                                         WHERE CASE
                                             WHEN valid_until IS NOT NULL AND valid_until < ?::date
                                                  AND UPPER(COALESCE(status,'')) NOT IN ('WON','LOST','EXPIRED') THEN 'EXPIRED'
                                             ELSE UPPER(COALESCE(status,'')) END NOT IN ('WON','LOST','EXPIRED')
                                     ) AS open_quotes,

                                     COUNT(*) FILTER (
                                         WHERE UPPER(COALESCE(status,'')) = 'WON'
                                     ) AS won_quotes,

                                     COUNT(*) FILTER (
                                         WHERE UPPER(COALESCE(status,'')) = 'LOST'
                                     ) AS lost_quotes,

                                     COUNT(*) FILTER (
                                         WHERE CASE
                                             WHEN valid_until IS NOT NULL AND valid_until < ?::date
                                                  AND UPPER(COALESCE(status,'')) NOT IN ('WON','LOST','EXPIRED') THEN 'EXPIRED'
                                             ELSE UPPER(COALESCE(status,'')) END = 'EXPIRED'
                                     ) AS expired_quotes,

                                     COALESCE(SUM(COALESCE(quoted_amount,0)),0)
                                         AS quoted_value,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN UPPER(COALESCE(status,'')) = 'WON'
                                                 THEN COALESCE(quoted_amount,0)
                                                 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS won_value,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN CASE
                                                     WHEN valid_until IS NOT NULL AND valid_until < ?::date
                                                          AND UPPER(COALESCE(status,'')) NOT IN ('WON','LOST','EXPIRED') THEN 'EXPIRED'
                                                     ELSE UPPER(COALESCE(status,'')) END NOT IN ('WON','LOST','EXPIRED')
                                                 THEN COALESCE(quoted_amount,0)
                                                 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS open_value

                                 FROM commercial_quotes
                                 WHERE tenant_id = ?
                                   AND quote_date BETWEEN ?::date AND ?::date
                                 """,
                (rs, rowNum) -> {
                    long won = rs.getLong("won_quotes");

                    long lost = rs.getLong("lost_quotes");

                    long decided = won + lost;

                    return new SalesPipelineSummary(
                            rs.getLong("total_quotes"),
                            rs.getLong("open_quotes"),
                            won,
                            lost,
                            rs.getLong("expired_quotes"),
                            nz(
                                    rs.getBigDecimal(
                                            "quoted_value")),
                            nz(
                                    rs.getBigDecimal(
                                            "won_value")),
                            nz(
                                    rs.getBigDecimal(
                                            "open_value")),
                            ratio(
                                    won,
                                    decided));
                },
                to,
                to,
                to,
                tenantId,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * TASKS
     * ========================================================================
     */

    private TaskSummary tasks(
            UUID tenantId,
            LocalDate from,
            LocalDate to) {
        return jdbc.queryForObject(
                """
                        SELECT
                        COUNT(*) FILTER (
                        WHERE UPPER(COALESCE(status,'')) NOT IN
                        ('COMPLETED','CANCELLED')
                        ) AS open_tasks,


                                     COUNT(*) FILTER (
                                         WHERE due_date = ?::date
                                           AND UPPER(COALESCE(status,'')) NOT IN
                                               ('COMPLETED','CANCELLED')
                                     ) AS due_today,

                                     COUNT(*) FILTER (
                                         WHERE due_date < ?::date
                                           AND UPPER(COALESCE(status,'')) NOT IN
                                               ('COMPLETED','CANCELLED')
                                     ) AS overdue_tasks

                                 FROM task_records
                                 WHERE tenant_id = ?
                                   AND (
                                       due_date BETWEEN ?::date AND ?::date
                                       OR due_date IS NULL
                                   )
                                 """,
                (rs, rowNum) -> new TaskSummary(
                        rs.getLong("open_tasks"),
                        rs.getLong("due_today"),
                        rs.getLong("overdue_tasks")),
                to,
                to,
                tenantId,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * FLEET
     * ========================================================================
     */

    private FleetSummary fleet(
            UUID tenantId) {
        return jdbc.queryForObject(
                """
                        SELECT
                        (SELECT COUNT(*)
                        FROM vehicles
                        WHERE tenant_id = ?) AS vehicles,


                                     (SELECT COUNT(*)
                                        FROM vehicles
                                       WHERE tenant_id = ?
                                         AND status = 'AVAILABLE') AS available_vehicles,

                                     (SELECT COUNT(*)
                                        FROM vehicles
                                       WHERE tenant_id = ?
                                         AND status = 'ON_TRIP') AS on_trip_vehicles,

                                     (SELECT COUNT(*)
                                        FROM vehicles
                                       WHERE tenant_id = ?
                                         AND status = 'MAINTENANCE') AS maintenance_vehicles,

                                     (SELECT COUNT(*)
                                        FROM drivers
                                       WHERE tenant_id = ?) AS drivers,

                                     (SELECT COUNT(*)
                                        FROM drivers
                                       WHERE tenant_id = ?
                                         AND status = 'AVAILABLE') AS available_drivers,

                                     (SELECT COUNT(*)
                                        FROM drivers
                                       WHERE tenant_id = ?
                                         AND status = 'ON_TRIP') AS on_trip_drivers
                                 """,
                (rs, rowNum) -> new FleetSummary(
                        rs.getLong("vehicles"),
                        rs.getLong("available_vehicles"),
                        rs.getLong("on_trip_vehicles"),
                        rs.getLong("maintenance_vehicles"),
                        rs.getLong("drivers"),
                        rs.getLong("available_drivers"),
                        rs.getLong("on_trip_drivers")),
                tenantId,
                tenantId,
                tenantId,
                tenantId,
                tenantId,
                tenantId,
                tenantId);

    }

    /*
     * 
     * ========================================================================
     * CUSTOMER PROFITABILITY
     * ========================================================================
     */

    private List<CustomerProfitability> customerProfitability(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        return jdbc.query(
                """
                        SELECT
                        COALESCE(
                        NULLIF(TRIM(client_name),''),
                        'Unknown'
                        ) AS customer,


                                     COUNT(*) AS shipments,

                                     COALESCE(
                                         SUM(
                                             COALESCE(
                                                 amount_billed_to_client,
                                                 amount_billed_to_client,
                                                 0
                                             )
                                         ),
                                         0
                                     ) AS revenue,

                                     COALESCE(
                                         SUM(COALESCE(supplier_cost,0)),
                                         0
                                     ) AS supplier_cost,

                                     COALESCE(
                                         SUM(
                                             COALESCE(other_cost,0)
                                         ),
                                         0
                                     ) AS other_cost

                                 FROM shipments
                                 WHERE tenant_id = ?
                                   AND UPPER(
                                       COALESCE(
                                           NULLIF(currency,''),
                                           ?
                                       )
                                   ) = ?
                                   AND COALESCE(
                                       date_opened,
                                       created_at::date
                                   ) BETWEEN ?::date AND ?::date

                                 GROUP BY
                                     COALESCE(
                                         NULLIF(TRIM(client_name),''),
                                         'Unknown'
                                     )

                                 ORDER BY revenue DESC, customer
                                 LIMIT 100
                                 """,
                (rs, rowNum) -> {
                    BigDecimal revenue = nz(
                            rs.getBigDecimal(
                                    "revenue"));

                    BigDecimal supplier = nz(
                            rs.getBigDecimal(
                                    "supplier_cost"));

                    BigDecimal other = nz(
                            rs.getBigDecimal(
                                    "other_cost"));

                    BigDecimal profit = revenue
                            .subtract(supplier)
                            .subtract(other);

                    return new CustomerProfitability(
                            rs.getString("customer"),
                            rs.getLong("shipments"),
                            revenue,
                            supplier,
                            other,
                            profit,
                            margin(
                                    profit,
                                    revenue));
                },
                tenantId,
                currency,
                currency,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * CARRIER PROFITABILITY
     * ========================================================================
     */

    private List<CarrierProfitability> carrierProfitability(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        return jdbc.query(
                """
                        SELECT
                        COALESCE(
                        NULLIF(TRIM(carrier_name),''),
                        NULLIF(TRIM(airline_used),''),
                        'Unknown'
                        ) AS carrier,


                                     COUNT(*) AS shipments,

                                     COALESCE(
                                         SUM(
                                             COALESCE(
                                                 amount_billed_to_client,
                                                 amount_billed_to_client,
                                                 0
                                             )
                                         ),
                                         0
                                     ) AS revenue,

                                     COALESCE(
                                         SUM(COALESCE(supplier_cost,0)),
                                         0
                                     ) AS supplier_cost,

                                     COALESCE(
                                         SUM(
                                             COALESCE(other_cost,0)
                                         ),
                                         0
                                     ) AS other_cost,

                                     COUNT(*) FILTER (
                                         WHERE EXISTS (
                                             SELECT 1
                                             FROM shipment_tracking_events e
                                             WHERE e.tenant_id = shipments.tenant_id
                                               AND e.shipment_id = shipments.id
                                               AND e.event_type = 'EXCEPTION'
                                         )
                                     ) AS exception_shipments

                                 FROM shipments
                                 WHERE tenant_id = ?
                                   AND UPPER(
                                       COALESCE(
                                           NULLIF(currency,''),
                                           ?
                                       )
                                   ) = ?
                                   AND COALESCE(
                                       date_opened,
                                       created_at::date
                                   ) BETWEEN ?::date AND ?::date

                                 GROUP BY
                                     COALESCE(
                                         NULLIF(TRIM(carrier_name),''),
                                         NULLIF(TRIM(airline_used),''),
                                         'Unknown'
                                     )

                                 ORDER BY revenue DESC, carrier
                                 LIMIT 100
                                 """,
                (rs, rowNum) -> {
                    BigDecimal revenue = nz(
                            rs.getBigDecimal(
                                    "revenue"));

                    BigDecimal supplier = nz(
                            rs.getBigDecimal(
                                    "supplier_cost"));

                    BigDecimal other = nz(
                            rs.getBigDecimal(
                                    "other_cost"));

                    BigDecimal profit = revenue
                            .subtract(supplier)
                            .subtract(other);

                    long shipments = rs.getLong("shipments");

                    return new CarrierProfitability(
                            rs.getString("carrier"),
                            shipments,
                            revenue,
                            supplier,
                            other,
                            profit,
                            margin(
                                    profit,
                                    revenue),
                            ratio(
                                    rs.getLong(
                                            "exception_shipments"),
                                    shipments));
                },
                tenantId,
                currency,
                currency,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * SHIPMENT PROFITABILITY
     * ========================================================================
     */

    private List<ShipmentProfitability> shipmentProfitability(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        return jdbc.query(
                """
                        SELECT
                        reference_code,
                        COALESCE(
                        date_opened,
                        created_at::date
                        ) AS date_opened,
                        COALESCE(
                        NULLIF(TRIM(client_name),''),
                        'Unknown'
                        ) AS customer,
                        COALESCE(
                        NULLIF(TRIM(carrier_name),''),
                        NULLIF(TRIM(airline_used),''),
                        'Unknown'
                        ) AS carrier,
                        transport_mode,
                        UPPER(
                        COALESCE(
                        NULLIF(currency,''),
                        ?
                        )
                        ) AS currency,
                        COALESCE(
                        chargeable_weight_kg,
                        GREATEST(
                        COALESCE(gross_weight_kg,0),
                        COALESCE(volumetric_weight_kg,0)
                        ),
                        0
                        ) AS chargeable_weight,


                                     COALESCE(
                                         amount_billed_to_client,
                                         amount_billed_to_client,
                                         0
                                     ) AS revenue,

                                     COALESCE(supplier_cost,0)
                                         AS supplier_cost,

                                     COALESCE(other_cost,0)
                                         AS other_cost,

                                     status

                                 FROM shipments
                                 WHERE tenant_id = ?
                                   AND UPPER(
                                       COALESCE(
                                           NULLIF(currency,''),
                                           ?
                                       )
                                   ) = ?
                                   AND COALESCE(
                                       date_opened,
                                       created_at::date
                                   ) BETWEEN ?::date AND ?::date

                                 ORDER BY
                                     COALESCE(
                                         amount_billed_to_client,
                                         amount_billed_to_client,
                                         0
                                     ) DESC,
                                     date_opened DESC
                                 LIMIT 500
                                 """,
                (rs, rowNum) -> {
                    BigDecimal revenue = nz(
                            rs.getBigDecimal(
                                    "revenue"));

                    BigDecimal supplier = nz(
                            rs.getBigDecimal(
                                    "supplier_cost"));

                    BigDecimal other = nz(
                            rs.getBigDecimal(
                                    "other_cost"));

                    BigDecimal profit = revenue
                            .subtract(supplier)
                            .subtract(other);

                    return new ShipmentProfitability(
                            rs.getString("reference_code"),
                            rs.getDate("date_opened")
                                    .toLocalDate(),
                            rs.getString("customer"),
                            rs.getString("carrier"),
                            rs.getString("transport_mode"),
                            rs.getString("currency"),
                            nz(
                                    rs.getBigDecimal(
                                            "chargeable_weight")),
                            revenue,
                            supplier,
                            other,
                            profit,
                            margin(
                                    profit,
                                    revenue),
                            rs.getString("status"));
                },
                currency,
                tenantId,
                currency,
                currency,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * MONTHLY TREND
     * ========================================================================
     *
     * Revenue/collections come from commercial invoices.
     * Shipment gross profit comes from operational profitability.
     */

    private List<MonthlyTrend> monthlyTrend(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            String currency) {
        return jdbc.query(
                """
                        WITH months AS (
                        SELECT generate_series(
                        date_trunc(
                        'month',
                        ?::date
                        ),
                        date_trunc(
                        'month',
                        ?::date
                        ),
                        INTERVAL '1 month'
                        )::date AS month
                        ),


                                 invoice_months AS (
                                     SELECT
                                         date_trunc(
                                             'month',
                                             issue_date
                                         )::date AS month,

                                         COALESCE(
                                             SUM(invoice_amount),
                                             0
                                         ) AS invoiced,

                                         COALESCE(
                                             SUM(amount_paid),
                                             0
                                         ) AS collected,

                                         COALESCE(
                                             SUM(
                                                 GREATEST(
                                                     invoice_amount - amount_paid,
                                                     0
                                                 )
                                             ),
                                             0
                                         ) AS outstanding

                                     FROM commercial_invoices
                                     WHERE tenant_id = ?
                                       AND UPPER(currency) = ?
                                       AND issue_date BETWEEN ?::date AND ?::date

                                     GROUP BY 1
                                 ),

                                 shipment_months AS (
                                     SELECT
                                         date_trunc(
                                             'month',
                                             COALESCE(
                                                 date_opened,
                                                 created_at::date
                                             )
                                         )::date AS month,

                                         COUNT(*) AS shipments,

                                         COALESCE(
                                             SUM(
                                                 COALESCE(
                                                     amount_billed_to_client,
                                                     amount_billed_to_client,
                                                     0
                                                 )
                                                 - COALESCE(supplier_cost,0)
                                                 - COALESCE(other_cost,0)
                                             ),
                                             0
                                         ) AS gross_profit

                                     FROM shipments
                                     WHERE tenant_id = ?
                                       AND UPPER(
                                           COALESCE(
                                               NULLIF(currency,''),
                                               ?
                                           )
                                       ) = ?
                                       AND COALESCE(
                                           date_opened,
                                           created_at::date
                                       ) BETWEEN ?::date AND ?::date

                                     GROUP BY 1
                                 )

                                 SELECT
                                     m.month,

                                     COALESCE(
                                         sm.shipments,
                                         0
                                     ) AS shipments,

                                     COALESCE(
                                         im.invoiced,
                                         0
                                     ) AS invoiced,

                                     COALESCE(
                                         im.collected,
                                         0
                                     ) AS collected,

                                     COALESCE(
                                         im.outstanding,
                                         0
                                     ) AS outstanding,

                                     COALESCE(
                                         sm.gross_profit,
                                         0
                                     ) AS gross_profit

                                 FROM months m

                                 LEFT JOIN invoice_months im
                                     ON im.month = m.month

                                 LEFT JOIN shipment_months sm
                                     ON sm.month = m.month

                                 ORDER BY m.month
                                 """,
                (rs, rowNum) -> new MonthlyTrend(
                        rs.getDate("month")
                                .toLocalDate()
                                .toString(),
                        rs.getLong("shipments"),
                        nz(
                                rs.getBigDecimal(
                                        "invoiced")),
                        nz(
                                rs.getBigDecimal(
                                        "collected")),
                        nz(
                                rs.getBigDecimal(
                                        "outstanding")),
                        nz(
                                rs.getBigDecimal(
                                        "gross_profit"))),
                from,
                to,
                tenantId,
                currency,
                from,
                to,
                tenantId,
                currency,
                currency,
                from,
                to);

    }

    /*
     * 
     * ========================================================================
     * RECEIVABLE AGING
     * ========================================================================
     */

    private List<ReceivablesAging> receivablesAging(
            UUID tenantId,
            LocalDate asOf,
            String currency) {
        return jdbc.query(
                """
                        WITH balances AS (
                        SELECT
                        GREATEST(
                        invoice_amount - amount_paid,
                        0
                        ) AS balance,
                        due_date
                        FROM commercial_invoices
                        WHERE tenant_id = ?
                        AND UPPER(currency) = ?
                        AND GREATEST(
                        invoice_amount - amount_paid,
                        0
                        ) > 0
                        ),


                                 bucketed AS (
                                     SELECT
                                         CASE
                                             WHEN due_date IS NULL
                                               OR due_date >= ?::date
                                                 THEN 'Current'

                                             WHEN (?::date - due_date) <= 30
                                                 THEN '1-30 Days'

                                             WHEN (?::date - due_date) <= 60
                                                 THEN '31-60 Days'

                                             WHEN (?::date - due_date) <= 90
                                                 THEN '61-90 Days'

                                             ELSE '90+ Days'
                                         END AS bucket,
                                         balance
                                     FROM balances
                                 )

                                 SELECT
                                     bucket,
                                     COALESCE(SUM(balance),0) AS balance,
                                     COUNT(*) AS invoice_count

                                 FROM bucketed

                                 GROUP BY bucket

                                 ORDER BY CASE bucket
                                     WHEN 'Current' THEN 0
                                     WHEN '1-30 Days' THEN 1
                                     WHEN '31-60 Days' THEN 2
                                     WHEN '61-90 Days' THEN 3
                                     ELSE 4
                                 END
                                 """,
                (rs, rowNum) -> new ReceivablesAging(
                        rs.getString("bucket"),
                        nz(
                                rs.getBigDecimal(
                                        "balance")),
                        rs.getLong("invoice_count")),
                tenantId,
                currency,
                asOf,
                asOf,
                asOf,
                asOf);

    }

    /*
     * 
     * ========================================================================
     * QUOTATION PIPELINE
     * ========================================================================
     */

    private List<QuoteStatus> quotationPipeline(
            UUID tenantId, LocalDate from, LocalDate to, String currency) {
        return jdbc.query(
                """
                        SELECT
                            CASE
                                WHEN valid_until IS NOT NULL AND valid_until < ?::date
                                     AND UPPER(COALESCE(status,'')) NOT IN ('WON','LOST','EXPIRED') THEN 'Expired'
                                ELSE COALESCE(NULLIF(TRIM(status),''),'Draft')
                            END AS status,
                            COUNT(*) AS count,
                            COALESCE(SUM(COALESCE(quoted_amount,0)),0) AS quoted_value
                        FROM commercial_quotes
                        WHERE tenant_id = ?
                          AND quote_date BETWEEN ?::date AND ?::date
                        GROUP BY 1
                        ORDER BY status
                        """,
                (rs, rowNum) -> new QuoteStatus(
                        rs.getString("status"), rs.getLong("count"), nz(rs.getBigDecimal("quoted_value"))),
                to, tenantId, from, to);

    }

    /*
     * 
     * ========================================================================
     * OPERATIONAL EXCEPTIONS
     * ========================================================================
     */

    private List<ExceptionSummary> operationalExceptions(
            UUID tenantId,
            LocalDate asOf) {
        return jdbc.query(
                """
                        SELECT
                        severity,
                        exception_type AS type,
                        reference,
                        message,
                        lane,
                        mode


                                 FROM (
                                     SELECT
                                         'HIGH' AS severity,
                                         'DELAYED_SHIPMENT' AS exception_type,
                                         s.reference_code AS reference,
                                         'ETA has passed without delivery' AS message,

                                         COALESCE(
                                             NULLIF(
                                                 TRIM(
                                                     s.origin_city_port
                                                 ),
                                                 ''
                                             ),
                                             s.origin_address,
                                             'Unknown'
                                         )
                                         || ' → ' ||
                                         COALESCE(
                                             NULLIF(
                                                 TRIM(
                                                     s.destination_city_port
                                                 ),
                                                 ''
                                             ),
                                             s.destination_address,
                                             'Unknown'
                                         ) AS lane,

                                         s.transport_mode AS mode,

                                         1 AS priority

                                     FROM shipments s

                                     WHERE s.tenant_id = ?
                                       AND s.eta IS NOT NULL
                                       AND s.eta < (?::date + INTERVAL '1 day')
                                       AND s.status NOT IN
                                           ('DELIVERED','COMPLETED','CANCELLED')

                                     UNION ALL

                                     SELECT
                                         CASE
                                             WHEN e.severity = 'CRITICAL'
                                                 THEN 'CRITICAL'
                                             WHEN e.severity = 'HIGH'
                                                 THEN 'HIGH'
                                             ELSE e.severity
                                         END,

                                         e.exception_type,

                                         s.reference_code,

                                         COALESCE(
                                             e.title,
                                             e.description,
                                             'Open operational exception'
                                         ),

                                         COALESCE(
                                             NULLIF(
                                                 TRIM(
                                                     s.origin_city_port
                                                 ),
                                                 ''
                                             ),
                                             s.origin_address,
                                             'Unknown'
                                         )
                                         || ' → ' ||
                                         COALESCE(
                                             NULLIF(
                                                 TRIM(
                                                     s.destination_city_port
                                                 ),
                                                 ''
                                             ),
                                             s.destination_address,
                                             'Unknown'
                                         ),

                                         s.transport_mode,

                                         0

                                     FROM logistics_exceptions e

                                     JOIN shipments s
                                       ON s.id = e.shipment_id
                                      AND s.tenant_id = e.tenant_id

                                     WHERE e.tenant_id = ?
                                       AND e.status = 'OPEN'

                                     UNION ALL

                                     SELECT
                                         'MEDIUM',
                                         'UNASSIGNED_SHIPMENT',
                                         s.reference_code,
                                         'Active shipment has no planned or in-progress route',

                                         COALESCE(
                                             NULLIF(
                                                 TRIM(
                                                     s.origin_city_port
                                                 ),
                                                 ''
                                             ),
                                             s.origin_address,
                                             'Unknown'
                                         )
                                         || ' → ' ||
                                         COALESCE(
                                             NULLIF(
                                                 TRIM(
                                                     s.destination_city_port
                                                 ),
                                                 ''
                                             ),
                                             s.destination_address,
                                             'Unknown'
                                         ),

                                         s.transport_mode,

                                         2

                                     FROM shipments s

                                     WHERE s.tenant_id = ?
                                       AND s.status NOT IN
                                           ('DELIVERED','COMPLETED','CANCELLED')

                                       AND NOT EXISTS (
                                           SELECT 1
                                           FROM trip_shipments ts
                                           JOIN trips t
                                             ON t.id = ts.trip_id
                                            AND t.tenant_id = ts.tenant_id

                                           WHERE ts.tenant_id = s.tenant_id
                                             AND ts.shipment_id = s.id
                                             AND t.status IN
                                                 ('PLANNED','IN_PROGRESS')
                                       )
                                 ) x

                                 ORDER BY
                                     CASE severity
                                         WHEN 'CRITICAL' THEN 0
                                         WHEN 'HIGH' THEN 1
                                         WHEN 'MEDIUM' THEN 2
                                         ELSE 3
                                     END,
                                     priority,
                                     reference

                                 LIMIT 100
                                 """,
                (rs, rowNum) -> new ExceptionSummary(
                        rs.getString("severity"),
                        rs.getString("type"),
                        rs.getString("reference"),
                        rs.getString("message"),
                        rs.getString("lane"),
                        rs.getString("mode")),
                tenantId,
                asOf,
                tenantId,
                tenantId);

    }

    /*
     * 
     * ========================================================================
     * LEGACY DASHBOARD
     * ========================================================================
     *
     * Existing /api/reports/dashboard clients continue to work.
     */

    public DashboardResponse dashboard() {
        UUID tenantId = requireTenant();

        return new DashboardResponse(
                shipmentsByStatus(tenantId),
                carrierLeadTimes(tenantId),
                carrierExceptionRates(tenantId),
                fleetUtilization(tenantId),
                tripStats(tenantId));

    }

    private List<StatusCount> shipmentsByStatus(
            UUID tenantId) {
        return jdbc.query(
                """
                        SELECT status, COUNT(*) AS count
                        FROM shipments
                        WHERE tenant_id = ?
                        GROUP BY status
                        ORDER BY status
                        """,
                (rs, rowNum) -> new StatusCount(
                        rs.getString("status"),
                        rs.getLong("count")),
                tenantId);
    }

    private List<CarrierLeadTime> carrierLeadTimes(
            UUID tenantId) {
        return jdbc.query(
                """
                        SELECT
                        s.carrier_name,
                        COUNT(DISTINCT s.id) AS delivered_count,


                                     ROUND(
                                         AVG(
                                             EXTRACT(
                                                 EPOCH FROM
                                                 (
                                                     d.occurred_at
                                                     - b.occurred_at
                                                 )
                                             ) / 3600.0
                                         )::numeric,
                                         2
                                     ) AS avg_lead_time_hours

                                 FROM shipments s

                                 JOIN shipment_tracking_events b
                                   ON b.shipment_id = s.id
                                  AND b.tenant_id = s.tenant_id
                                  AND b.event_type = 'BOOKED'

                                 JOIN shipment_tracking_events d
                                   ON d.shipment_id = s.id
                                  AND d.tenant_id = s.tenant_id
                                  AND d.event_type = 'DELIVERED'

                                 WHERE s.tenant_id = ?
                                   AND s.status = 'DELIVERED'
                                   AND s.carrier_name IS NOT NULL

                                 GROUP BY s.carrier_name
                                 ORDER BY s.carrier_name
                                 """,
                (rs, rowNum) -> new CarrierLeadTime(
                        rs.getString(
                                "carrier_name"),
                        rs.getLong(
                                "delivered_count"),
                        rs.getObject(
                                "avg_lead_time_hours") != null
                                        ? rs.getDouble(
                                                "avg_lead_time_hours")
                                        : null),
                tenantId);

    }

    private List<CarrierExceptionRate> carrierExceptionRates(
            UUID tenantId) {
        return jdbc.query(
                """
                        SELECT
                        s.carrier_name,
                        COUNT(DISTINCT s.id) AS total_shipments,


                                     COUNT(
                                         DISTINCT CASE
                                             WHEN e.event_type = 'EXCEPTION'
                                             THEN s.id
                                         END
                                     ) AS shipments_with_exceptions

                                 FROM shipments s

                                 LEFT JOIN shipment_tracking_events e
                                   ON e.shipment_id = s.id
                                  AND e.tenant_id = s.tenant_id

                                 WHERE s.tenant_id = ?
                                   AND s.carrier_name IS NOT NULL

                                 GROUP BY s.carrier_name
                                 ORDER BY s.carrier_name
                                 """,
                (rs, rowNum) -> {
                    long total = rs.getLong(
                            "total_shipments");

                    long exceptions = rs.getLong(
                            "shipments_with_exceptions");

                    return new CarrierExceptionRate(
                            rs.getString(
                                    "carrier_name"),
                            total,
                            exceptions,
                            total == 0
                                    ? 0
                                    : 100.0
                                            * exceptions
                                            / total);
                },
                tenantId);

    }

    private List<VehicleStatusCount> fleetUtilization(
            UUID tenantId) {
        return jdbc.query(
                """
                        SELECT status, COUNT(*) AS count
                        FROM vehicles
                        WHERE tenant_id = ?
                        GROUP BY status
                        ORDER BY status
                        """,
                (rs, rowNum) -> new VehicleStatusCount(
                        rs.getString("status"),
                        rs.getLong("count")),
                tenantId);
    }

    private TripStats tripStats(
            UUID tenantId) {
        return jdbc.queryForObject(
                """
                        SELECT
                        COALESCE(
                        SUM(
                        CASE
                        WHEN status = 'COMPLETED'
                        THEN 1 ELSE 0
                        END
                        ),
                        0
                        ) AS completed,


                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN status = 'CANCELLED'
                                                 THEN 1 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS cancelled,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN status = 'IN_PROGRESS'
                                                 THEN 1 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS in_progress,

                                     COALESCE(
                                         SUM(
                                             CASE
                                                 WHEN status = 'PLANNED'
                                                 THEN 1 ELSE 0
                                             END
                                         ),
                                         0
                                     ) AS planned

                                 FROM trips
                                 WHERE tenant_id = ?
                                 """,
                (rs, rowNum) -> new TripStats(
                        rs.getLong("completed"),
                        rs.getLong("cancelled"),
                        rs.getLong("in_progress"),
                        rs.getLong("planned")),
                tenantId);

    }

    /*
     * 
     * ========================================================================
     * HELPERS
     * ========================================================================
     */

    private String reportingCurrency(
            UUID tenantId) {
        String value = jdbc.queryForObject(
                """
                        SELECT COALESCE(
                        (
                        SELECT default_currency
                        FROM tenant_profiles
                        WHERE tenant_id = ?
                        ),
                        'USD'
                        )
                        """,
                String.class,
                tenantId);

        if (value == null
                || value.isBlank()) {
            return "USD";
        }

        return value
                .trim()
                .toUpperCase();

    }

    private boolean hasMixedCurrencies(
            UUID tenantId,
            String reportingCurrency) {
        Integer count = jdbc.queryForObject(
                """
                        SELECT COUNT(DISTINCT currency)
                        FROM (
                        SELECT
                        UPPER(
                        COALESCE(
                        NULLIF(currency,''),
                        ?
                        )
                        ) AS currency
                        FROM shipments
                        WHERE tenant_id = ?


                                             UNION

                                             SELECT
                                                 UPPER(currency) AS currency
                                             FROM commercial_invoices
                                             WHERE tenant_id = ?
                                         ) currencies
                                         """,
                Integer.class,
                reportingCurrency,
                tenantId,
                tenantId);

        return count != null && count > 1;

    }

    private BigDecimal scalarMoney(
            String sql,
            Object... args) {
        BigDecimal value = jdbc.queryForObject(
                sql,
                BigDecimal.class,
                args);

        return nz(value);

    }

    private static BigDecimal nz(
            BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    private static BigDecimal ratio(
            long numerator,
            long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        BigDecimal.valueOf(denominator),
                        2,
                        RoundingMode.HALF_UP);

    }

    private static BigDecimal margin(
            BigDecimal profit,
            BigDecimal revenue) {
        if (revenue == null
                || revenue.signum() == 0) {
            return BigDecimal.ZERO;
        }

        return profit
                .multiply(BigDecimal.valueOf(100))
                .divide(
                        revenue,
                        2,
                        RoundingMode.HALF_UP);

    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();

        if (tenantId == null) {
            throw new IllegalStateException(
                    "Tenant context is not available");
        }

        return tenantId;

    }
}
