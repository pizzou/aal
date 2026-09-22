package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class FreightAuditService {
    private final JdbcTemplate db;

    public FreightAuditService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) { this.db = db; }

    @Transactional
    public Map<String,Object> audit(UUID shipmentId) {
        UUID tenant = TenantContext.getTenantId();
        Map<String,Object> shipment = one("""
                SELECT id, COALESCE(amount_billed_to_client,client_revenue,0) AS revenue,
                       COALESCE(supplier_cost,0) AS supplier_cost,
                       COALESCE(other_cost,0)+COALESCE(other_expenses,0) AS operational_cost
                  FROM shipments WHERE id=? AND tenant_id=?
                """, shipmentId, tenant);

        Map<String,Object> invoice = oneOrNull("""
                SELECT id,invoice_amount,amount_paid,currency
                  FROM commercial_invoices
                 WHERE tenant_id=? AND shipment_id=?
                 ORDER BY issue_date DESC,created_at DESC LIMIT 1
                """, tenant, shipmentId);

        BigDecimal expectedRevenue = dec(shipment.get("revenue"));
        BigDecimal actualRevenue = invoice == null ? BigDecimal.ZERO : dec(invoice.get("invoice_amount"));

        BigDecimal supplierBills = scalar("SELECT COALESCE(SUM(amount),0) FROM finance_supplier_bills WHERE tenant_id=? AND shipment_id=?", tenant, shipmentId);
        BigDecimal legCosts = scalar("SELECT COALESCE(SUM(c.amount),0) FROM transport_leg_costs c JOIN transport_legs l ON l.id=c.leg_id WHERE c.tenant_id=? AND l.tenant_id=? AND l.shipment_id=?", tenant, tenant, shipmentId);
        BigDecimal recordedSupplier = dec(shipment.get("supplier_cost"));
        BigDecimal supplierCost = supplierBills.signum() > 0 ? supplierBills : (legCosts.signum() > 0 ? legCosts : recordedSupplier);
        BigDecimal operationalCost = dec(shipment.get("operational_cost"));

        BigDecimal variance = money(actualRevenue.subtract(expectedRevenue));
        BigDecimal totalCost = money(supplierCost.add(operationalCost));
        BigDecimal grossMargin = money(actualRevenue.subtract(totalCost));
        BigDecimal marginPercent = actualRevenue.signum() == 0
                ? BigDecimal.ZERO
                : grossMargin.multiply(BigDecimal.valueOf(100)).divide(actualRevenue, 4, RoundingMode.HALF_UP);

        boolean revenueMismatch = variance.abs().compareTo(new BigDecimal("0.01")) > 0;
        boolean negativeMargin = grossMargin.signum() < 0;
        String status = revenueMismatch || negativeMargin ? "REVIEW" : "PASS";
        String findings = "{\"revenueVariance\":" + variance +
                ",\"supplierBills\":" + supplierBills +
                ",\"transportLegCosts\":" + legCosts +
                ",\"recordedSupplierCost\":" + recordedSupplier +
                ",\"negativeMargin\":" + negativeMargin + "}";

        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO freight_audit_results(
                    id,tenant_id,shipment_id,invoice_id,expected_revenue,actual_revenue,
                    supplier_cost,operational_cost,variance,gross_margin,margin_percent,
                    status,findings_json,audited_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, tenant, shipmentId, invoice == null ? null : invoice.get("id"),
                expectedRevenue, actualRevenue, supplierCost, operationalCost, variance,
                grossMargin, marginPercent, status, findings, currentUser());
        return one("SELECT * FROM freight_audit_results WHERE id=? AND tenant_id=?", id, tenant);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> latest(UUID shipmentId) {
        return one("SELECT * FROM freight_audit_results WHERE tenant_id=? AND shipment_id=? ORDER BY audited_at DESC LIMIT 1", TenantContext.getTenantId(), shipmentId);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> queue(String status) {
        if (status == null || status.isBlank()) {
            return db.queryForList("SELECT * FROM freight_audit_results WHERE tenant_id=? ORDER BY audited_at DESC LIMIT 100", TenantContext.getTenantId());
        }
        return db.queryForList("SELECT * FROM freight_audit_results WHERE tenant_id=? AND status=? ORDER BY audited_at DESC LIMIT 100", TenantContext.getTenantId(), status.trim().toUpperCase(Locale.ROOT));
    }

    private BigDecimal scalar(String sql, Object... args) {
        BigDecimal value = db.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String,Object> one(String sql, Object... args) { return db.queryForMap(sql,args); }
    private Map<String,Object> oneOrNull(String sql, Object... args) { try { return db.queryForMap(sql,args); } catch (EmptyResultDataAccessException e) { return null; } }
    private static BigDecimal dec(Object value) { return value instanceof BigDecimal b ? b : value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private static BigDecimal money(BigDecimal value) { return dec(value).setScale(2, RoundingMode.HALF_UP); }

    private UUID currentUser() {
        try {
            Object p = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (p instanceof com.logiplatform.security.TenantPrincipal tp) return tp.userId();
        } catch (Exception ignored) { }
        return null;
    }
}
