package com.logiplatform.service;

import com.logiplatform.dto.ShipmentDtos.CreateShipmentRequest;
import com.logiplatform.dto.ShipmentDtos.ShipmentResponse;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Durable idempotency boundary for shipment creation. The database row is
 * locked before the shipment is created, so concurrent retries with the same
 * key cannot create two shipments.
 */
@Service
public class ShipmentCreationIdempotencyService {

    private final JdbcTemplate db;
    private final ShipmentRepository shipments;

    public ShipmentCreationIdempotencyService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            ShipmentRepository shipments) {
        this.db = db;
        this.shipments = shipments;
    }

    public String normalize(String key) {
        if (key == null || key.isBlank()) return null;
        String value = key.trim();
        if (value.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must not exceed 255 characters");
        }
        return value;
    }

    public String requestHash(CreateShipmentRequest request) {
        String canonical = String.join("\n",
                value(request.referenceCode()),
                value(request.originAddress()),
                value(request.destinationAddress()),
                value(request.transportMode()),
                value(request.carrierName()),
                value(request.carrierReferenceNumber()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash shipment request", ex);
        }
    }

    @Transactional
    public ShipmentResponse findExisting(String key, String requestHash) {
        UUID tenant = requireTenant();
        var rows = db.query(
                "SELECT request_hash, shipment_id FROM shipment_creation_idempotency WHERE tenant_id=? AND idempotency_key=? FOR UPDATE",
                (rs, rowNum) -> new Existing(rs.getString("request_hash"), rs.getObject("shipment_id", UUID.class)),
                tenant, key);
        if (rows.isEmpty()) return null;
        Existing existing = rows.get(0);
        if (!existing.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used with different shipment data");
        }
        if (existing.shipmentId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The shipment creation request is already in progress");
        }
        Shipment shipment = shipments.findByIdAndTenantId(existing.shipmentId(), tenant)
                .orElse(null);
        if (shipment == null) {
            // A shipment may have been removed by an administrative cleanup or
            // legacy-data repair while its idempotency row remained. Treat that
            // row as stale and allow the original request to be recreated rather
            // than permanently returning HTTP 409 for the same browser request.
            db.update("""
                DELETE FROM shipment_creation_idempotency
                 WHERE tenant_id=? AND idempotency_key=? AND request_hash=?
                """, tenant, key, requestHash);
            return null;
        }
        return ShipmentResponse.from(shipment);
    }

    @Transactional
    public void reserve(String key, String requestHash) {
        UUID tenant = requireTenant();
        int inserted = db.update("""
                INSERT INTO shipment_creation_idempotency(tenant_id,idempotency_key,request_hash)
                VALUES(?,?,?)
                ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
                """, tenant, key, requestHash);
        var rows = db.query(
                "SELECT request_hash, shipment_id FROM shipment_creation_idempotency WHERE tenant_id=? AND idempotency_key=? FOR UPDATE",
                (rs, rowNum) -> new Existing(rs.getString("request_hash"), rs.getObject("shipment_id", UUID.class)),
                tenant, key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Unable to reserve shipment idempotency key");
        }
        Existing existing = rows.get(0);
        if (!existing.requestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency-Key was already used with different shipment data");
        }
        if (inserted == 0 && existing.shipmentId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Shipment creation was already completed for this Idempotency-Key");
        }
    }

    @Transactional
    public void complete(String key, String requestHash, UUID shipmentId) {
        UUID tenant = requireTenant();
        int updated = db.update("""
                UPDATE shipment_creation_idempotency
                   SET shipment_id=?, completed_at=now()
                 WHERE tenant_id=? AND idempotency_key=? AND request_hash=? AND shipment_id IS NULL
                """, shipmentId, tenant, key, requestHash);
        if (updated != 1) {
            throw new IllegalStateException("Unable to finalize shipment idempotency record");
        }
    }

    private UUID requireTenant() {
        try {
            return TenantContext.getTenantId();
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private record Existing(String requestHash, UUID shipmentId) {}
}
