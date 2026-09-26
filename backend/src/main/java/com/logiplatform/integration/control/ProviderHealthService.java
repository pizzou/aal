package com.logiplatform.integration.control;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class ProviderHealthService {
    private final JdbcTemplate db;
    private final AirCargoProviderRegistry registry;
    private final CircuitBreakerService breaker;

    public ProviderHealthService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db, AirCargoProviderRegistry registry, CircuitBreakerService breaker) {
        this.db = db; this.registry = registry; this.breaker = breaker;
    }

    public Map<String,Object> current() {
        UUID tenant = TenantContext.getTenantId();
        List<Map<String,Object>> accounts = db.queryForList("SELECT a.code,a.provider_name,a.protocol,a.enabled,a.status,a.health_status,a.base_url,a.last_success_at,a.last_failure_at,a.last_error,MIN(c.expires_at) FILTER (WHERE c.status='ACTIVE') AS credential_expires_at FROM integration_accounts a LEFT JOIN integration_credential_versions c ON c.account_id=a.id AND c.tenant_id=a.tenant_id WHERE a.tenant_id=? GROUP BY a.id ORDER BY a.provider_name", tenant);
        AirCargoProviderPort p = registry.active();
        Map<String,Object> active = new LinkedHashMap<>();
        active.put("code", p.providerCode());
        active.put("capabilities", p.capabilities());
        active.put("circuitBreaker", safeBreaker(p.providerCode()));
        active.put("configured", p.capabilities().booking() || p.capabilities().flightStatus());
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now()); out.put("activeProvider", active); out.put("accounts", accounts);
        return out;
    }

    public Map<String,Object> record(String provider, String status, long latencyMs, Integer httpStatus, String errorCode) {
        UUID tenant = TenantContext.getTenantId();
        db.update("INSERT INTO integration_health_samples(tenant_id,provider_code,status,latency_ms,http_status,error_code) VALUES(?,?,?,?,?,?)", tenant, provider, status, latencyMs, httpStatus, errorCode);
        if ("HEALTHY".equalsIgnoreCase(status)) breaker.success(provider); else breaker.failure(provider);
        db.update("UPDATE integration_accounts SET health_status=?,last_success_at=CASE WHEN ?='HEALTHY' THEN now() ELSE last_success_at END,last_failure_at=CASE WHEN ?<>'HEALTHY' THEN now() ELSE last_failure_at END,last_error=?,updated_at=now() WHERE tenant_id=? AND code=?", status, status.toUpperCase(Locale.ROOT), status.toUpperCase(Locale.ROOT), errorCode, tenant, provider);
        return Map.of("provider",provider,"status",status,"latencyMs",latencyMs);
    }

    private Map<String,Object> safeBreaker(String provider) {
        try { return breaker.status(provider); } catch (Exception ignored) { return Map.of("state","CLOSED","failureCount",0); }
    }
}
