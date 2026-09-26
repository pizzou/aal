package com.logiplatform.service.control;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ReconciliationTaskService {
    private final JdbcTemplate db; private final int maxAttempts;
    public ReconciliationTaskService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,@Value("${integration.reconciliation.max-attempts:12}") int maxAttempts){this.db=db;this.maxAttempts=Math.max(1,maxAttempts);}
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW) public UUID enqueue(String provider,String operation,String aggregateType,UUID aggregateId,String idempotencyKey,String externalReference,String error){UUID id=UUID.randomUUID();db.update("INSERT INTO integration_reconciliation_tasks(id,tenant_id,provider_code,operation_type,aggregate_type,aggregate_id,idempotency_key,external_reference,status,last_error) VALUES(?,?,?,?,?,?,?,?, 'OPEN',?) ON CONFLICT DO NOTHING",id,TenantContext.getTenantId(),provider,operation,aggregateType,aggregateId,idempotencyKey,externalReference,error);return id;}
    @Transactional public void markAttempt(UUID id,String error){db.update("UPDATE integration_reconciliation_tasks SET attempts=attempts+1,last_attempt_at=now(),last_error=?,status=CASE WHEN attempts+1>=? THEN 'DLQ' ELSE 'OPEN' END,next_attempt_at=now() + (LEAST(3600,POWER(2,GREATEST(attempts,0))*5)::text || ' seconds')::interval WHERE tenant_id=? AND id=?",error,maxAttempts,TenantContext.getTenantId(),id);}
    @Transactional public void resolve(UUID id){db.update("UPDATE integration_reconciliation_tasks SET status='RESOLVED',resolved_at=now(),last_error=NULL WHERE tenant_id=? AND id=?",TenantContext.getTenantId(),id);}
    public List<Map<String,Object>> queue(int limit){return db.queryForList("SELECT * FROM integration_reconciliation_tasks WHERE tenant_id=? AND status IN ('OPEN','RECONCILING') AND next_attempt_at<=now() ORDER BY next_attempt_at,id LIMIT ?",TenantContext.getTenantId(),Math.min(Math.max(limit,1),200));}
    public Map<String,Object> metrics(){var r=db.queryForMap("SELECT COUNT(*) FILTER(WHERE status IN ('OPEN','RECONCILING')) open_count,COUNT(*) FILTER(WHERE status='DLQ') dlq_count,MIN(created_at) oldest FROM integration_reconciliation_tasks WHERE tenant_id=?",TenantContext.getTenantId());return r;}
}
