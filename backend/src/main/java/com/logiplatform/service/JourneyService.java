package com.logiplatform.service;

import com.logiplatform.dto.AdvancedEnterpriseDtos.JourneyCreateRequest;
import com.logiplatform.dto.JourneyDtos.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class JourneyService {
    private final JdbcTemplate db;

    public JourneyService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Journey createOrUpdate(UUID shipmentId, JourneyCreateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        assertShipment(tenantId, shipmentId);
        if (!shipmentId.equals(request.shipmentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment ID mismatch");
        }

        String status = normalizeStatus(request.status());
        db.update("""
                INSERT INTO shipment_journeys
                    (id, tenant_id, shipment_id, journey_reference, service_type, status)
                VALUES
                    (gen_random_uuid(), ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, shipment_id)
                DO UPDATE SET
                    journey_reference = EXCLUDED.journey_reference,
                    service_type = EXCLUDED.service_type,
                    status = EXCLUDED.status,
                    updated_at = now()
                """,
                tenantId, shipmentId, clean(request.journeyReference()), clean(request.serviceType()), status);

        refresh(tenantId, shipmentId);
        return get(shipmentId);
    }

    @Transactional
    public Journey refresh(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        assertShipment(tenantId, shipmentId);
        refresh(tenantId, shipmentId);
        return get(shipmentId);
    }

    @Transactional(readOnly = true)
    public Journey get(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        assertShipment(tenantId, shipmentId);
        ensureJourney(tenantId, shipmentId);

        Map<String, Object> header = db.queryForMap("""
                SELECT id, journey_reference, service_type, planned_start, planned_end,
                       actual_start, actual_end, unified_eta, status, customer_visible
                  FROM shipment_journeys
                 WHERE shipment_id = ? AND tenant_id = ?
                """, shipmentId, tenantId);

        List<Leg> legs = db.query("""
                SELECT id, sequence_no, mode, origin, destination, carrier_name,
                       carrier_reference, planned_departure, planned_arrival,
                       actual_departure, actual_arrival, status
                  FROM transport_legs
                 WHERE shipment_id = ? AND tenant_id = ?
                 ORDER BY sequence_no, planned_departure NULLS LAST, id
                """, (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new Leg(
                    id,
                    rs.getInt("sequence_no"),
                    rs.getString("mode"),
                    rs.getString("origin"),
                    rs.getString("destination"),
                    rs.getString("carrier_name"),
                    rs.getString("carrier_reference"),
                    instant(rs.getTimestamp("planned_departure")),
                    instant(rs.getTimestamp("planned_arrival")),
                    instant(rs.getTimestamp("actual_departure")),
                    instant(rs.getTimestamp("actual_arrival")),
                    rs.getString("status"),
                    milestones(tenantId, id),
                    costs(tenantId, id),
                    documents(tenantId, id));
        }, shipmentId, tenantId);

        String currentShipmentStatus = db.queryForObject(
                "SELECT status FROM shipments WHERE id=? AND tenant_id=?",
                String.class, shipmentId, tenantId);

        return new Journey(
                uuid(header.get("id")),
                shipmentId,
                string(header.get("journey_reference")),
                string(header.get("service_type")),
                instant(header.get("planned_start")),
                instant(header.get("planned_end")),
                instant(header.get("actual_start")),
                instant(header.get("actual_end")),
                instant(header.get("unified_eta")),
                string(header.get("status")),
                Boolean.TRUE.equals(header.get("customer_visible")),
                legs,
                currentShipmentStatus);
    }

    private void refresh(UUID tenantId, UUID shipmentId) {
        List<Map<String, Object>> legs = db.queryForList("""
                SELECT planned_departure, planned_arrival, actual_departure, actual_arrival, status
                  FROM transport_legs
                 WHERE shipment_id=? AND tenant_id=?
                 ORDER BY sequence_no
                """, shipmentId, tenantId);

        Instant plannedStart = legs.stream().map(r -> instant(r.get("planned_departure")))
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant plannedEnd = legs.stream().map(r -> instant(r.get("planned_arrival")))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        Instant actualStart = legs.stream().map(r -> instant(r.get("actual_departure")))
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant actualEnd = legs.stream().map(r -> instant(r.get("actual_arrival")))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        Instant unifiedEta = legs.stream().map(r -> {
            Instant actual = instant(r.get("actual_arrival"));
            Instant planned = instant(r.get("planned_arrival"));
            String status = string(r.get("status"));
            if ("DELIVERED".equalsIgnoreCase(status) || actual != null) return null;
            return planned;
        }).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(plannedEnd);

        String status = deriveStatus(legs);
        db.update("""
                UPDATE shipment_journeys
                   SET planned_start=?, planned_end=?, actual_start=?, actual_end=?,
                       unified_eta=?, status=?, updated_at=now()
                 WHERE shipment_id=? AND tenant_id=?
                """, plannedStart, plannedEnd, actualStart, actualEnd, unifiedEta, status, shipmentId, tenantId);
    }

    private void ensureJourney(UUID tenantId, UUID shipmentId) {
        Integer count = db.queryForObject(
                "SELECT count(*) FROM shipment_journeys WHERE shipment_id=? AND tenant_id=?",
                Integer.class, shipmentId, tenantId);
        if (count == null || count == 0) {
            String reference = "AAL-" + shipmentId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
            db.update("""
                    INSERT INTO shipment_journeys
                        (id, tenant_id, shipment_id, journey_reference, service_type, status)
                    VALUES (gen_random_uuid(), ?, ?, ?, 'MULTIMODAL', 'PLANNED')
                    """, tenantId, shipmentId, reference);
            refresh(tenantId, shipmentId);
        }
    }

    private void assertShipment(UUID tenantId, UUID shipmentId) {
        Integer count = db.queryForObject(
                "SELECT count(*) FROM shipments WHERE id=? AND tenant_id=?",
                Integer.class, shipmentId, tenantId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
        }
    }

    private List<Milestone> milestones(UUID tenantId, UUID legId) {
        return db.query("""
                SELECT id, milestone_type, location, planned_at, actual_at, status, notes
                  FROM transport_leg_milestones
                 WHERE leg_id=? AND tenant_id=?
                 ORDER BY COALESCE(actual_at,planned_at), created_at
                """, (rs, row) -> new Milestone(
                rs.getObject("id", UUID.class),
                rs.getString("milestone_type"),
                rs.getString("location"),
                instant(rs.getTimestamp("planned_at")),
                instant(rs.getTimestamp("actual_at")),
                rs.getString("status"),
                rs.getString("notes")), legId, tenantId);
    }

    private List<Cost> costs(UUID tenantId, UUID legId) {
        return db.query("""
                SELECT id, description, amount, currency, supplier
                  FROM transport_leg_costs
                 WHERE leg_id=? AND tenant_id=? ORDER BY created_at
                """, (rs, row) -> new Cost(
                rs.getObject("id", UUID.class),
                rs.getString("description"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("supplier")), legId, tenantId);
    }

    private List<Document> documents(UUID tenantId, UUID legId) {
        return db.query("""
                SELECT id, document_type, document_uri, customer_visible, status
                  FROM transport_leg_documents
                 WHERE leg_id=? AND tenant_id=? ORDER BY created_at DESC
                """, (rs, row) -> new Document(
                rs.getObject("id", UUID.class),
                rs.getString("document_type"),
                rs.getString("document_uri"),
                rs.getBoolean("customer_visible"),
                rs.getString("status")), legId, tenantId);
    }

    private static String deriveStatus(List<Map<String, Object>> legs) {
        if (legs.isEmpty()) return "PLANNED";
        boolean allDelivered = legs.stream().allMatch(r -> "DELIVERED".equalsIgnoreCase(string(r.get("status"))));
        if (allDelivered) return "DELIVERED";
        boolean anyException = legs.stream().anyMatch(r -> "EXCEPTION".equalsIgnoreCase(string(r.get("status"))));
        if (anyException) return "EXCEPTION";
        boolean anyActive = legs.stream().anyMatch(r -> Set.of("IN_TRANSIT", "DEPARTED", "ARRIVED").contains(string(r.get("status")).toUpperCase(Locale.ROOT)));
        return anyActive ? "IN_PROGRESS" : "PLANNED";
    }

    private static String normalizeStatus(String value) {
        String v = clean(value);
        if (v == null || v.isBlank()) return "PLANNED";
        v = v.toUpperCase(Locale.ROOT);
        if (!Set.of("PLANNED", "IN_PROGRESS", "DELIVERED", "EXCEPTION", "CANCELLED").contains(v)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported journey status: " + v);
        }
        return v;
    }

    private static String clean(String value) { return value == null ? null : value.trim(); }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static UUID uuid(Object value) { return value instanceof UUID u ? u : UUID.fromString(String.valueOf(value)); }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        return Instant.parse(String.valueOf(value));
    }
}
