package com.logiplatform.integration.control;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Provider-specific outbound limiter. Persisted policy lives in integration_rate_limits; counters are local per instance. */
@Service
public class ProviderRateLimiter {
    private final JdbcTemplate db; private final int defaultLimit; private final Map<String,Window> windows=new ConcurrentHashMap<>();
    public ProviderRateLimiter(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,@Value("${integration.rate-limit.default-per-minute:120}") int defaultLimit){this.db=db;this.defaultLimit=Math.max(1,defaultLimit);}
    public void acquire(String provider){int limit=limit(provider);String key=provider+":"+(System.currentTimeMillis()/60000);Window w=windows.computeIfAbsent(key,k->new Window());int count=w.count.incrementAndGet();if(count>limit){w.count.decrementAndGet();throw new com.logiplatform.integration.control.ExternalOperationException("PROVIDER_RATE_LIMITED",null,false,429);}}
    private int limit(String provider){try{var rows=db.queryForList("SELECT requests_per_minute FROM integration_rate_limits WHERE tenant_id=? AND scope='PROVIDER' AND provider_code=? AND enabled=true",TenantContext.getTenantId(),provider);if(!rows.isEmpty())return Math.max(1,((Number)rows.get(0).get("requests_per_minute")).intValue());}catch(Exception ignored){}return defaultLimit;}
    private static final class Window{final AtomicInteger count=new AtomicInteger();}
}
