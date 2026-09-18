package com.logiplatform.service;

import com.logiplatform.dto.AalBusinessDtos.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Native AAL business engine. Spreadsheet calculations are implemented here as
 * domain rules; no workbook formula is stored or executed by the application.
 */
@Service
public class AalBusinessEngineService {
    private final JdbcTemplate jdbc;

    public AalBusinessEngineService(@Qualifier("reportingJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Cockpit cockpit(LocalDate from, LocalDate to, String requestedCurrency) {
        UUID tenant = tenant();
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) throw new IllegalArgumentException("to must not be before from");
        String currency = currency(tenant, requestedCurrency);

        Summary s = jdbc.queryForObject("""
            SELECT COUNT(*) shipments,
                   COUNT(*) FILTER (WHERE status NOT IN ('DELIVERED','COMPLETED','CANCELLED')) active,
                   COUNT(*) FILTER (WHERE status IN ('DELIVERED','COMPLETED')) delivered,
                   COALESCE(SUM(gross_weight_kg),0) gross_weight,
                   COALESCE(SUM(chargeable_weight_kg),0) chargeable_weight,
                   COALESCE(SUM(amount_billed_to_client),0) billed,
                   COALESCE(SUM(amount_paid_by_client),0) collected,
                   COALESCE(SUM(GREATEST(COALESCE(amount_billed_to_client,0)-COALESCE(amount_paid_by_client,0),0)),0) receivable,
                   COALESCE(SUM(COALESCE(supplier_cost,0)+COALESCE(other_cost,0)),0) total_cost,
                   COALESCE(SUM(COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)),0) gross_profit,
                   COALESCE(SUM(COALESCE(amount_billed_to_client,0)-COALESCE(amount_paid_to_supply,0)-COALESCE(other_expenses,0)),0) net_income
              FROM shipments
             WHERE tenant_id=? AND date_opened>=? AND date_opened<=?
               AND UPPER(COALESCE(currency,?))=?
            """, (rs,n)->new Summary(
                rs.getLong("shipments"),rs.getLong("active"),rs.getLong("delivered"),
                nz(rs.getBigDecimal("gross_weight")),nz(rs.getBigDecimal("chargeable_weight")),
                nz(rs.getBigDecimal("billed")),nz(rs.getBigDecimal("collected")),nz(rs.getBigDecimal("receivable")),
                nz(rs.getBigDecimal("total_cost")),nz(rs.getBigDecimal("gross_profit")),nz(rs.getBigDecimal("net_income"))),
            tenant,start,end,currency,currency);

        long openTasks = count("SELECT COUNT(*) FROM task_records WHERE tenant_id=? AND UPPER(COALESCE(status,'')) NOT IN ('COMPLETED','CANCELLED')", tenant);
        long overdueTasks = count("SELECT COUNT(*) FROM task_records WHERE tenant_id=? AND UPPER(COALESCE(status,'')) NOT IN ('COMPLETED','CANCELLED') AND due_date<?", tenant, LocalDate.now());
        long openQuotes = count("SELECT COUNT(*) FROM commercial_quotes WHERE tenant_id=? AND UPPER(COALESCE(status,'')) NOT IN ('WON','LOST','EXPIRED')", tenant);
        long wonQuotes = count("SELECT COUNT(*) FROM commercial_quotes WHERE tenant_id=? AND UPPER(COALESCE(status,''))='WON'", tenant);

        return new Cockpit(start,end,currency,s.shipments,s.active,s.delivered,s.grossWeight,s.chargeableWeight,
                s.billed,s.collected,s.receivable,s.cost,s.grossProfit,s.billed.signum()==0?BigDecimal.ZERO:pct(s.grossProfit,s.billed),
                s.netIncome,openTasks,overdueTasks,openQuotes,wonQuotes,monthly(tenant,start,end,currency),lanes(tenant,start,end,currency));
    }

    @Transactional(readOnly = true)
    public ShipmentFinancial shipmentFinancial(UUID id) {
        UUID tenant = tenant();
        return jdbc.queryForObject("""
            SELECT id,reference_code,currency,gross_weight_kg,volumetric_weight_kg,
                   GREATEST(COALESCE(gross_weight_kg,0),COALESCE(volumetric_weight_kg,0)) chargeable,
                   COALESCE(supplier_cost,0) supplier_cost,COALESCE(other_cost,0) other_cost,
                   COALESCE(amount_billed_to_client,0) billed,COALESCE(amount_paid_by_client,0) collected,
                   COALESCE(amount_paid_to_supply,0) supplier_paid,COALESCE(other_expenses,0) other_expenses,
                   payment_status
              FROM shipments WHERE tenant_id=? AND id=?
            """, (rs,n)-> {
                BigDecimal supplier=nz(rs.getBigDecimal("supplier_cost")), other=nz(rs.getBigDecimal("other_cost"));
                BigDecimal billed=nz(rs.getBigDecimal("billed")), collected=nz(rs.getBigDecimal("collected"));
                BigDecimal supplierPaid=nz(rs.getBigDecimal("supplier_paid")), expenses=nz(rs.getBigDecimal("other_expenses"));
                BigDecimal cost=supplier.add(other), profit=billed.subtract(cost), net=billed.subtract(supplierPaid).subtract(expenses);
                return new ShipmentFinancial(rs.getObject("id",UUID.class),rs.getString("reference_code"),rs.getString("currency"),
                    rs.getBigDecimal("gross_weight_kg"),rs.getBigDecimal("volumetric_weight_kg"),rs.getBigDecimal("chargeable"),
                    supplier,other,cost,billed,collected,billed.subtract(collected).max(BigDecimal.ZERO),supplierPaid,expenses,profit,
                    billed.signum()==0?BigDecimal.ZERO:pct(profit,billed),net,rs.getString("payment_status"));
            }, tenant,id);
    }

    private List<Monthly> monthly(UUID tenant,LocalDate start,LocalDate end,String currency){
        return jdbc.query("""
          SELECT TO_CHAR(date_trunc('month',date_opened),'YYYY-MM') AS month_label,COUNT(*) shipments,
                 COALESCE(SUM(amount_billed_to_client),0) revenue,COALESCE(SUM(amount_paid_by_client),0) collected,
                 COALESCE(SUM(GREATEST(COALESCE(amount_billed_to_client,0)-COALESCE(amount_paid_by_client,0),0)),0) receivable,
                 COALESCE(SUM(COALESCE(supplier_cost,0)+COALESCE(other_cost,0)),0) cost,
                 COALESCE(SUM(COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)),0) gross_profit
            FROM shipments WHERE tenant_id=? AND date_opened>=? AND date_opened<=? AND UPPER(COALESCE(currency,?))=?
           GROUP BY 1 ORDER BY 1""",
          (rs,n)->new Monthly(rs.getString("month_label"),rs.getLong("shipments"),nz(rs.getBigDecimal("revenue")),nz(rs.getBigDecimal("collected")),nz(rs.getBigDecimal("receivable")),nz(rs.getBigDecimal("cost")),nz(rs.getBigDecimal("gross_profit"))),tenant,start,end,currency,currency);
    }

    private List<Lane> lanes(UUID tenant,LocalDate start,LocalDate end,String currency){
        return jdbc.query("""
          SELECT COALESCE(origin_city_port,origin_address,'Unknown') origin,
                 COALESCE(destination_city_port,destination_address,'Unknown') destination,
                 COUNT(*) shipments,COALESCE(SUM(amount_billed_to_client),0) revenue,
                 COALESCE(SUM(COALESCE(amount_billed_to_client,0)-COALESCE(supplier_cost,0)-COALESCE(other_cost,0)),0) gross_profit,
                 COUNT(*) FILTER (WHERE eta IS NOT NULL AND eta < (CURRENT_TIMESTAMP) AND status NOT IN ('DELIVERED','COMPLETED','CANCELLED')) delayed
            FROM shipments WHERE tenant_id=? AND date_opened>=? AND date_opened<=? AND UPPER(COALESCE(currency,?))=?
           GROUP BY 1,2 ORDER BY revenue DESC LIMIT 12""",
          (rs,n)->new Lane(rs.getString("origin"),rs.getString("destination"),rs.getLong("shipments"),nz(rs.getBigDecimal("revenue")),nz(rs.getBigDecimal("gross_profit")),rs.getLong("delayed")),tenant,start,end,currency,currency);
    }

    private long count(String sql,Object... args){Long v=jdbc.queryForObject(sql,Long.class,args);return v==null?0:v;}
    private String currency(UUID tenant,String requested){
        String c=requested==null||requested.isBlank()?jdbc.queryForObject("SELECT COALESCE((SELECT default_currency FROM tenant_profiles WHERE tenant_id=?),'USD')",String.class,tenant):requested;
        return c==null||c.isBlank()?"USD":c.trim().toUpperCase();
    }
    private UUID tenant(){UUID t=TenantContext.getTenantId();if(t==null)throw new IllegalStateException("Tenant context is not available");return t;}
    private static BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static BigDecimal pct(BigDecimal a,BigDecimal b){return a.multiply(BigDecimal.valueOf(100)).divide(b,2,RoundingMode.HALF_UP);}
    private record Summary(long shipments,long active,long delivered,BigDecimal grossWeight,BigDecimal chargeableWeight,BigDecimal billed,BigDecimal collected,BigDecimal receivable,BigDecimal cost,BigDecimal grossProfit,BigDecimal netIncome){}
}
