package com.logiplatform.controller;

import com.logiplatform.dto.LogisticsOperationsDtos;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/logistics")
public class LogisticsOperationsController {
    private final JdbcTemplate jdbc;

    public LogisticsOperationsController(@Qualifier("tenantJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private UUID tenant() {
        return TenantContext.getTenantId();
    }

    @GetMapping("/control-tower")
    public Map<String, Object> tower() {
        UUID t = tenant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("openExceptions", jdbc.queryForObject(
                "select count(*) from logistics_exceptions where tenant_id=? and status='OPEN'", Long.class, t));
        m.put("activeShipments", jdbc.queryForObject(
                "select count(*) from shipments where tenant_id=? and status='IN_TRANSIT'", Long.class, t));
        m.put("oceanBookings",
                jdbc.queryForObject(
                        "select count(*) from ocean_bookings where tenant_id=? and status in ('REQUESTED','CONFIRMED')",
                        Long.class, t));
        m.put("plannedLegs", jdbc.queryForObject(
                "select count(*) from transport_plan_legs where tenant_id=? and status='PLANNED'", Long.class, t));
        m.put("coldChainCargo", jdbc.queryForObject(
                "select count(*) from cargo_items where tenant_id=? and temperature_controlled=true", Long.class, t));
        m.put("dangerousGoods", jdbc.queryForObject(
                "select count(*) from cargo_items where tenant_id=? and dangerous_goods=true", Long.class, t));
        return m;
    }

    @GetMapping("/rates")
    public List<Map<String, Object>> rates() {
        return jdbc.queryForList(
                "select id,quote_number as \"quoteNumber\",service_name as \"serviceName\",mode,origin_code as \"originCode\",destination_code as \"destinationCode\",currency,base_amount as \"baseAmount\",fuel_surcharge as \"fuelSurcharge\",security_surcharge as \"securitySurcharge\",handling_amount as \"handlingAmount\",customs_amount as \"customsAmount\",other_amount as \"otherAmount\",valid_from as \"validFrom\",valid_until as \"validUntil\",status from logistics_rates where tenant_id=? order by created_at desc",
                tenant());
    }

    @PostMapping("/rates")
    public Map<String, Object> rate(@RequestBody LogisticsOperationsDtos.RateRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into logistics_rates(id,tenant_id,quote_number,shipment_id,service_name,mode,origin_code,destination_code,currency,base_amount,fuel_surcharge,security_surcharge,handling_amount,customs_amount,other_amount,valid_from,valid_until,status,terms) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant(), r.quoteNumber(), r.shipmentId(), r.serviceName(), r.mode(), r.originCode(),
                r.destinationCode(), r.currency(), n(r.baseAmount()), n(r.fuelSurcharge()), n(r.securitySurcharge()),
                n(r.handlingAmount()), n(r.customsAmount()), n(r.otherAmount()), r.validFrom(), r.validUntil(), "DRAFT",
                r.terms());
        return Map.of("id", id, "quoteNumber", r.quoteNumber(), "status", "DRAFT");
    }

    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions() {
        return jdbc.queryForList(
                "select id,shipment_id as \"shipmentId\",severity,exception_type as \"exceptionType\",title,description,status,owner,due_at as \"dueAt\",created_at as \"createdAt\" from logistics_exceptions where tenant_id=? order by case severity when 'CRITICAL' then 1 when 'HIGH' then 2 when 'MEDIUM' then 3 else 4 end,due_at nulls last",
                tenant());
    }

    @PostMapping("/exceptions")
    public Map<String, Object> exception(@RequestBody LogisticsOperationsDtos.ExceptionRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into logistics_exceptions(id,tenant_id,shipment_id,severity,exception_type,title,description,status,owner,due_at) values(?,?,?,?,?,?,?,?,?,?)",
                id, tenant(), r.shipmentId(), r.severity(), r.exceptionType(), r.title(), r.description(), "OPEN",
                r.owner(), r.dueAt());
        return Map.of("id", id, "status", "OPEN");
    }

    @GetMapping("/documents/{shipmentId}")
    public List<Map<String, Object>> documents(@PathVariable UUID shipmentId) {
        return jdbc.queryForList(
                "select id,document_type as \"documentType\",document_number as \"documentNumber\",version_no as \"versionNo\",status,file_uri as \"fileUri\",issued_at as \"issuedAt\",expires_at as \"expiresAt\" from logistics_documents where tenant_id=? and shipment_id=? order by document_type,version_no desc",
                tenant(), shipmentId);
    }

    private static BigDecimal n(BigDecimal x) {
        return x == null ? BigDecimal.ZERO : x;
    }
}
