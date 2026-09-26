package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

@Service
public class AirlineIntegrationAttemptService {
    private final JdbcTemplate db;
    public AirlineIntegrationAttemptService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) { this.db = db; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID start(String provider, String operation, String idempotencyKey, String correlationId, String requestBody) {
        UUID id = UUID.randomUUID(); UUID tenant = TenantContext.getTenantId();
        db.update("INSERT INTO integration_attempts(id,tenant_id,integration_code,operation,direction,idempotency_key,correlation_id,status,request_hash,started_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, provider, operation, "OUTBOUND", idempotencyKey, correlationId, "STARTED", hash(requestBody), Instant.now(), Instant.now());
        return id;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(UUID id, int status, String responseBody) {
        db.update("UPDATE integration_attempts SET status='SUCCEEDED',response_code=?,response_hash=?,completed_at=now() WHERE id=? AND tenant_id=?", status, hash(responseBody), id, TenantContext.getTenantId());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(UUID id, Integer status, String error) {
        db.update("UPDATE integration_attempts SET status='FAILED',response_code=?,error_detail=?,completed_at=now() WHERE id=? AND tenant_id=?", status, error == null ? "integration failed" : error.substring(0, Math.min(error.length(), 4000)), id, TenantContext.getTenantId());
    }
    public java.util.Map<String,Object> health(String provider) {
        UUID tenant = TenantContext.getTenantId();
        var rows = db.queryForList("SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE status='FAILED') AS failed, MAX(created_at) AS last_attempt FROM integration_attempts WHERE tenant_id=? AND integration_code=?", tenant, provider);
        if (rows.isEmpty()) { java.util.Map<String,Object> empty=new java.util.LinkedHashMap<>(); empty.put("total",0); empty.put("failed",0); empty.put("lastAttempt",null); return empty; } return rows.get(0);
    }

    public static String hash(String value) {
        if (value == null) return null;
        try { byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format("%02x",x)); return b.toString(); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
