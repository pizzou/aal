package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

@Service
public class SmsNotificationService {
    private final JdbcTemplate db;
    private final SmsSenderPort sender;

    public SmsNotificationService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db, SmsSenderPort sender) {
        this.db = db;
        this.sender = sender;
    }

    @Transactional
    public Map<String,Object> send(UUID shipmentId, String recipient, String message, String idempotencyKey) {
        UUID tenant = TenantContext.getTenantId();
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isBlank() || key.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid Idempotency-Key is required");
        String phone = recipient == null ? "" : recipient.trim();
        if (!phone.matches("\\+[1-9][0-9]{7,14}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient must be an E.164 phone number");
        String body = message == null ? "" : message.trim();
        if (body.isBlank() || body.length() > 1600) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SMS message must contain 1-1600 characters");
        if (shipmentId != null) {
            Integer shipmentExists = db.queryForObject("SELECT count(*) FROM shipments WHERE id=? AND tenant_id=?", Integer.class, shipmentId, tenant);
            if (shipmentExists == null || shipmentExists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
        }

        Map<String,Object> existing = oneOrNull("SELECT id,status,provider,provider_reference,error_detail FROM sms_delivery_attempts WHERE tenant_id=? AND idempotency_key=?", tenant, key);
        if (existing != null) return existing;

        UUID id = UUID.randomUUID();
        db.update("INSERT INTO sms_delivery_attempts(id,tenant_id,shipment_id,recipient,provider,message_body,idempotency_key,status,attempts) VALUES(?,?,?,?,?,?,?,?,1)",
                id, tenant, shipmentId, phone, "UNRESOLVED", body, key, "QUEUED");
        SmsSenderPort.SendResult result = sender.send(phone, body);
        db.update("UPDATE sms_delivery_attempts SET provider=?,status=?,provider_reference=?,error_detail=?,sent_at=CASE WHEN ? THEN now() ELSE sent_at END WHERE id=? AND tenant_id=?",
                result.provider(), result.sent() ? "SENT" : "FAILED", result.providerReference(), result.errorDetail(), result.sent(), id, tenant);
        return one("SELECT id,status,provider,provider_reference,error_detail FROM sms_delivery_attempts WHERE id=? AND tenant_id=?", id, tenant);
    }

    private Map<String,Object> one(String sql,Object... args) { return db.queryForMap(sql,args); }
    private Map<String,Object> oneOrNull(String sql,Object... args) {
        try { return db.queryForMap(sql,args); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { return null; }
    }
}
