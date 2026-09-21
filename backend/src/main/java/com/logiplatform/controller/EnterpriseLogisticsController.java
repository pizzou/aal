package com.logiplatform.controller;

import com.logiplatform.dto.EnterpriseLogisticsDtos;

import com.logiplatform.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/enterprise")
public class EnterpriseLogisticsController {
    private final JdbcTemplate jdbc;

    public EnterpriseLogisticsController(@Qualifier("tenantJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private UUID tenant() {
        return TenantContext.getTenantId();
    }

    @GetMapping("/profile")
    public Map<String, Object> profile() {
        return jdbc.queryForMap(
                "select id,tenant_id as \"tenantId\",legal_name as \"legalName\",trading_name as \"tradingName\",registration_number as \"registrationNumber\",tax_number as \"taxNumber\",country_code as \"countryCode\",city,timezone,default_currency as \"defaultCurrency\",phone,email,website,logo_uri as \"logoUri\",settings_json as \"settingsJson\" from tenant_profiles where tenant_id=?",
                tenant());
    }

    /**
     * Legacy enterprise quote endpoints intentionally no longer write to a second
     * quote store. Sales & Quotations (/api/commercial/quotes) is canonical.
     */
    @GetMapping("/quotes")
    public void legacyQuotes() {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE,
                "Enterprise quote storage was consolidated into Sales & Quotations. Use /api/commercial/quotes.");
    }

    @PostMapping("/quotes")
    public void legacyCreateQuote() {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE,
                "Enterprise quote storage was consolidated into Sales & Quotations. Use /api/commercial/quotes.");
    }

    @PostMapping("/quotes/{quoteId}/lines")
    public void legacyQuoteLine(@PathVariable UUID quoteId) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE,
                "Enterprise quote lines were consolidated into Sales & Quotations.");
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return jdbc.queryForList(
                "select id,shipment_id as \"shipmentId\",title,description,task_type as \"taskType\",priority,status,assigned_to as \"assignedTo\",due_at as \"dueAt\",completed_at as \"completedAt\" from logistics_tasks where tenant_id=? order by case priority when 'CRITICAL' then 1 when 'HIGH' then 2 when 'MEDIUM' then 3 else 4 end,due_at nulls last",
                tenant());
    }

    @PostMapping("/tasks")
    public Map<String, Object> task(@Valid @RequestBody EnterpriseLogisticsDtos.TaskRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into logistics_tasks(id,tenant_id,shipment_id,title,description,task_type,priority,assigned_to,due_at) values(?,?,?,?,?,?,?,?,?)",
                id, tenant(), r.shipmentId(), r.title(), r.description(), r.taskType(), r.priority(), r.assignedTo(),
                r.dueAt());
        return Map.of("id", id, "status", "OPEN");
    }

    @GetMapping("/milestones/{shipmentId}")
    public List<Map<String, Object>> milestones(@PathVariable UUID shipmentId) {
        return jdbc.queryForList(
                "select id,milestone_code as \"milestoneCode\",milestone_name as \"milestoneName\",sequence_no as \"sequenceNo\",planned_at as \"plannedAt\",estimated_at as \"estimatedAt\",actual_at as \"actualAt\",status,source,notes from shipment_milestones where tenant_id=? and shipment_id=? order by sequence_no",
                tenant(), shipmentId);
    }

    @PostMapping("/milestones/{shipmentId}")
    public Map<String, Object> milestone(@PathVariable UUID shipmentId,
            @Valid @RequestBody EnterpriseLogisticsDtos.MilestoneRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into shipment_milestones(id,tenant_id,shipment_id,milestone_code,milestone_name,sequence_no,planned_at,estimated_at,status,source,notes) values(?,?,?,?,?,?,?,?,?,?,?) on conflict(tenant_id,shipment_id,milestone_code) do update set milestone_name=excluded.milestone_name,planned_at=excluded.planned_at,estimated_at=excluded.estimated_at,status=excluded.status,notes=excluded.notes,updated_at=now()",
                id, tenant(), shipmentId, r.code(), r.name(), r.sequenceNo(), r.plannedAt(), r.estimatedAt(),
                r.status(), r.source(), r.notes());
        return Map.of("shipmentId", shipmentId, "milestoneCode", r.code());
    }

    @GetMapping("/claims")
    public List<Map<String, Object>> claims() {
        return jdbc.queryForList(
                "select id,shipment_id as \"shipmentId\",claim_number as \"claimNumber\",claim_type as \"claimType\",status,responsible_party as \"responsibleParty\",claimed_amount as \"claimedAmount\",currency,description,filed_at as \"filedAt\",settlement_amount as \"settlementAmount\" from logistics_claims where tenant_id=? order by created_at desc",
                tenant());
    }

    @PostMapping("/claims")
    public Map<String, Object> claim(@Valid @RequestBody EnterpriseLogisticsDtos.ClaimRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into logistics_claims(id,tenant_id,shipment_id,claim_number,claim_type,responsible_party,claimed_amount,currency,description,filed_at) values(?,?,?,?,?,?,?,?,?,?)",
                id, tenant(), r.shipmentId(), r.claimNumber(), r.claimType(), r.responsibleParty(),
                n(r.claimedAmount()), r.currency(), r.description(), Instant.now());
        return Map.of("id", id, "status", "OPEN");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        UUID t = tenant();
        return Map.of("status", "OPERATIONAL", "tenantId", t, "pendingIntegrations", jdbc.queryForObject(
                "select count(*) from integration_outbox where tenant_id=? and status='PENDING'", Long.class, t),
                "openTasks",
                jdbc.queryForObject(
                        "select count(*) from logistics_tasks where tenant_id=? and status='OPEN'", Long.class, t),
                "openClaims",
                jdbc.queryForObject(
                        "select count(*) from logistics_claims where tenant_id=? and status not in ('CLOSED','SETTLED')",
                        Long.class, t));
    }

    private static BigDecimal n(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
