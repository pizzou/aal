package com.logiplatform.service;

import com.logiplatform.model.AalImportBatch;
import com.logiplatform.repository.AalImportBatchRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns Excel migration from a one-way import into a measurable reconciliation
 * process. The client can verify both record counts and key financial totals
 * before retiring the spreadsheets.
 */
@Service
public class ExcelReconciliationService {
    private final JdbcTemplate db;
    private final AalImportBatchRepository importBatches;

    public ExcelReconciliationService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            AalImportBatchRepository importBatches) {
        this.db = db;
        this.importBatches = importBatches;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latest() {
        UUID tenant = TenantContext.getTenantId();
        AalImportBatch batch = importBatches.findTopByTenantIdOrderByStartedAtDesc(tenant).orElse(null);
        if (batch == null) {
            return Map.of("status", "NO_IMPORT", "reconciled", false);
        }

        AalImportBatch.AalImportCounts expected = batch.counts();
        Map<String, Object> actual = actualCounts(tenant);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batch.getId());
        result.put("sourceFile", batch.getSourceFilename());
        result.put("sourceSha256", batch.getSourceSha256());
        result.put("batchStatus", batch.getStatus());
        result.put("expected", Map.of(
                "shipments", expected.shipments(),
                "quotations", expected.quotations(),
                "invoices", expected.invoices(),
                "clients", expected.clients(),
                "partners", expected.partners(),
                "tasks", expected.tasks(),
                "expenses", expected.expenses()));
        result.put("actual", actual);
        result.put("deltas", Map.of(
                "shipments", delta(expected.shipments(), number(actual.get("shipments"))),
                "quotations", delta(expected.quotations(), number(actual.get("quotations"))),
                "invoices", delta(expected.invoices(), number(actual.get("invoices"))),
                "clients", delta(expected.clients(), number(actual.get("clients"))),
                "partners", delta(expected.partners(), number(actual.get("partners"))),
                "tasks", delta(expected.tasks(), number(actual.get("tasks"))),
                "expenses", delta(expected.expenses(), number(actual.get("expenses")))));

        result.put("financial", Map.of(
                "invoiceRevenue", scalar("SELECT COALESCE(SUM(invoice_amount),0) FROM commercial_invoices WHERE tenant_id=?", tenant),
                "shipmentClientRevenue", scalar("SELECT COALESCE(SUM(COALESCE(amount_billed_to_client,client_revenue,0)),0) FROM shipments WHERE tenant_id=?", tenant),
                "supplierCost", scalar("SELECT COALESCE(SUM(COALESCE(supplier_cost,0)),0) FROM shipments WHERE tenant_id=?", tenant)));

        boolean countsMatch = ((Map<?, ?>) result.get("deltas")).values().stream()
                .allMatch(v -> number(v) == 0L);
        result.put("reconciled", countsMatch && "COMPLETED".equalsIgnoreCase(batch.getStatus()));
        result.put("status", countsMatch ? "PASS" : "REVIEW");
        return result;
    }

    private Map<String, Object> actualCounts(UUID tenant) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipments", scalarLong("SELECT count(*) FROM shipments WHERE tenant_id=?", tenant));
        result.put("quotations", scalarLong("SELECT count(*) FROM commercial_quotes WHERE tenant_id=?", tenant));
        result.put("invoices", scalarLong("SELECT count(*) FROM commercial_invoices WHERE tenant_id=?", tenant));
        result.put("clients", scalarLong("SELECT count(*) FROM client_records WHERE tenant_id=?", tenant));
        result.put("partners", scalarLong("SELECT count(*) FROM partner_records WHERE tenant_id=?", tenant));
        result.put("tasks", scalarLong("SELECT count(*) FROM task_records WHERE tenant_id=?", tenant));
        result.put("expenses", scalarLong("SELECT count(*) FROM expense_records WHERE tenant_id=?", tenant));
        return result;
    }

    private long scalarLong(String sql, Object... args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal scalar(String sql, Object... args) {
        BigDecimal value = db.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long delta(long expected, long actual) { return actual - expected; }

    private static long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null) return 0L;
        return Long.parseLong(value.toString());
    }
}
