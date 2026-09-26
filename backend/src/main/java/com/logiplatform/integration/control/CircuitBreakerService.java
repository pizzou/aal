package com.logiplatform.integration.control;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class CircuitBreakerService {
    private final JdbcTemplate db;
    private final int threshold;
    private final long cooldownSeconds;

    public CircuitBreakerService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                 @Value("${integration.circuit-breaker.failure-threshold:5}") int threshold,
                                 @Value("${integration.circuit-breaker.cooldown-seconds:30}") long cooldownSeconds) {
        this.db = db;
        this.threshold = Math.max(1, threshold);
        this.cooldownSeconds = Math.max(1, cooldownSeconds);
    }

    @Transactional
    public boolean allow(String provider) {
        UUID tenant = TenantContext.getTenantId();
        List<Map<String,Object>> rows = db.queryForList("SELECT state,next_probe_at FROM integration_circuit_breakers WHERE tenant_id=? AND provider_code=?", tenant, provider);
        if (rows.isEmpty()) return true;
        Map<String,Object> row = rows.get(0);
        String state = String.valueOf(row.get("state"));
        if (!"OPEN".equals(state)) return true;
        Object next = row.get("next_probe_at");
        if (next instanceof java.sql.Timestamp ts && ts.toInstant().isBefore(Instant.now())) {
            db.update("UPDATE integration_circuit_breakers SET state='HALF_OPEN',updated_at=now() WHERE tenant_id=? AND provider_code=? AND state='OPEN'", tenant, provider);
            return true;
        }
        return false;
    }

    @Transactional
    public void success(String provider) { success(provider,0L,200); }

    @Transactional
    public void success(String provider,long latencyMs,int httpStatus) {
        UUID tenant = TenantContext.getTenantId();
        db.update("INSERT INTO integration_circuit_breakers(tenant_id,provider_code,state,failure_count,last_success_at,updated_at) VALUES(?,?,'CLOSED',0,now(),now()) " +
                "ON CONFLICT(tenant_id,provider_code) DO UPDATE SET state='CLOSED',failure_count=0,next_probe_at=NULL,last_success_at=now(),updated_at=now()", tenant, provider);
        db.update("INSERT INTO integration_accounts(id,tenant_id,code,provider_name,protocol,enabled,status,health_status,last_success_at) VALUES(gen_random_uuid(),?,?,?,'HTTP',true,'ACTIVE','HEALTHY',now()) ON CONFLICT(tenant_id,code) DO UPDATE SET health_status='HEALTHY',status='ACTIVE',last_success_at=now(),last_error=NULL,updated_at=now()", tenant, provider, provider);
        db.update("INSERT INTO integration_health_samples(id,tenant_id,provider_code,status,latency_ms,http_status) VALUES(gen_random_uuid(),?,?,'HEALTHY',?,?)", tenant, provider, latencyMs, httpStatus);
    }

    @Transactional
    public void failure(String provider) { failure(provider,0L,null); }

    @Transactional
    public void failure(String provider,long latencyMs,Integer httpStatus) {
        UUID tenant = TenantContext.getTenantId();
        db.update("INSERT INTO integration_circuit_breakers(tenant_id,provider_code,state,failure_count,last_failure_at,updated_at) VALUES(?,?,'CLOSED',1,now(),now()) " +
                "ON CONFLICT(tenant_id,provider_code) DO UPDATE SET failure_count=integration_circuit_breakers.failure_count+1,last_failure_at=now(),updated_at=now()", tenant, provider);
        db.update("UPDATE integration_circuit_breakers SET state=CASE WHEN failure_count>=? THEN 'OPEN' ELSE state END, opened_at=CASE WHEN failure_count>=? THEN now() ELSE opened_at END, next_probe_at=CASE WHEN failure_count>=? THEN now() + (? || ' seconds')::interval ELSE next_probe_at END, updated_at=now() WHERE tenant_id=? AND provider_code=?",
                threshold, threshold, threshold, cooldownSeconds, tenant, provider);
        db.update("INSERT INTO integration_accounts(id,tenant_id,code,provider_name,protocol,enabled,status,health_status,last_failure_at,last_error) VALUES(gen_random_uuid(),?,?,?,'HTTP',true,'ACTIVE','DOWN',now(),'PROVIDER_FAILURE') ON CONFLICT(tenant_id,code) DO UPDATE SET health_status=CASE WHEN (SELECT state FROM integration_circuit_breakers WHERE tenant_id=? AND provider_code=?)='OPEN' THEN 'DOWN' ELSE 'DEGRADED' END,last_failure_at=now(),last_error='PROVIDER_FAILURE',updated_at=now()", tenant, provider, provider, tenant, provider);
        db.update("INSERT INTO integration_health_samples(id,tenant_id,provider_code,status,latency_ms,http_status,error_code) VALUES(gen_random_uuid(),?,?,'DOWN',?,?,?)", tenant, provider, latencyMs, httpStatus, "PROVIDER_FAILURE");
    }

    public Map<String,Object> status(String provider) {
        UUID tenant = TenantContext.getTenantId();
        return db.queryForMap("SELECT provider_code,state,failure_count,opened_at,next_probe_at,last_failure_at,last_success_at,updated_at FROM integration_circuit_breakers WHERE tenant_id=? AND provider_code=?", tenant, provider);
    }
}
