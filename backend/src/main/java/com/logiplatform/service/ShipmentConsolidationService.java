package com.logiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class ShipmentConsolidationService {
    private final JdbcTemplate db;

    public ShipmentConsolidationService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Map<String,Object> create(String reference, String mode, String masterReference,
                                     String origin, String destination, Instant departure,
                                     Instant arrival, String notes) {
        UUID tenant = TenantContext.getTenantId();
        String ref = required(reference, "consolidation reference");
        String normalizedMode = required(mode, "mode").toUpperCase(Locale.ROOT);
        try {
            UUID id = UUID.randomUUID();
            db.update("""
                INSERT INTO shipment_consolidations
                    (id,tenant_id,consolidation_reference,mode,master_reference,origin_code,destination_code,planned_departure,planned_arrival,notes)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, tenant, ref, normalizedMode, blank(masterReference), blank(origin), blank(destination), departure, arrival, notes);
            return get(id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A consolidation with reference '" + ref + "' already exists");
        }
    }

    @Transactional
    public Map<String,Object> addMember(UUID consolidationId, UUID shipmentId, String houseReference) {
        UUID tenant = TenantContext.getTenantId();
        requireConsolidation(consolidationId);
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM shipments WHERE id=? AND tenant_id=?", Integer.class, shipmentId, tenant);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
        try {
            db.update("""
                INSERT INTO shipment_consolidation_members(tenant_id,consolidation_id,shipment_id,house_reference)
                VALUES(?,?,?,?)
                """, tenant, consolidationId, shipmentId, blank(houseReference));
            db.update("UPDATE shipment_consolidations SET updated_at=now() WHERE id=? AND tenant_id=?", consolidationId, tenant);
            return get(consolidationId);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment is already assigned to a consolidation");
        }
    }

    @Transactional
    public void removeMember(UUID consolidationId, UUID shipmentId) {
        UUID tenant = TenantContext.getTenantId();
        requireConsolidation(consolidationId);
        db.update("DELETE FROM shipment_consolidation_members WHERE tenant_id=? AND consolidation_id=? AND shipment_id=?",
                tenant, consolidationId, shipmentId);
        db.update("UPDATE shipment_consolidations SET updated_at=now() WHERE id=? AND tenant_id=?", consolidationId, tenant);
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(UUID id) {
        UUID tenant = TenantContext.getTenantId();
        Map<String,Object> result;
        try {
            result = db.queryForMap("SELECT id,consolidation_reference,mode,master_reference,origin_code,destination_code,status,planned_departure,planned_arrival,notes,created_at,updated_at FROM shipment_consolidations WHERE id=? AND tenant_id=?", id, tenant);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Consolidation not found");
        }
        result.put("members", db.queryForList("""
            SELECT m.shipment_id,m.house_reference,m.role,s.reference_code,s.status,s.transport_mode
              FROM shipment_consolidation_members m
              JOIN shipments s ON s.id=m.shipment_id AND s.tenant_id=m.tenant_id
             WHERE m.tenant_id=? AND m.consolidation_id=?
             ORDER BY m.created_at
            """, tenant, id));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list() {
        UUID tenant = TenantContext.getTenantId();
        return db.queryForList("""
            SELECT c.id,c.consolidation_reference,c.mode,c.master_reference,c.origin_code,c.destination_code,
                   c.status,c.planned_departure,c.planned_arrival,COUNT(m.id) AS member_count
              FROM shipment_consolidations c
              LEFT JOIN shipment_consolidation_members m ON m.consolidation_id=c.id AND m.tenant_id=c.tenant_id
             WHERE c.tenant_id=?
             GROUP BY c.id
             ORDER BY c.created_at DESC
            """, tenant);
    }

    private void requireConsolidation(UUID id) { get(id); }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        return value.trim();
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
