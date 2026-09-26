package com.logiplatform.service.control;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class JobExecutionService {
    private final JdbcTemplate db;
    public JobExecutionService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.db=db;}
    @Transactional public Optional<UUID> acquire(String jobName,String executionKey){UUID tenant=TenantContext.getTenantId();UUID id=UUID.randomUUID();int n=db.update("INSERT INTO integration_job_executions(id,tenant_id,job_name,execution_key,status) VALUES(?,?,?,?, 'RUNNING') ON CONFLICT(tenant_id,job_name,execution_key) DO NOTHING",id,tenant,jobName,executionKey);return n==0?Optional.empty():Optional.of(id);}
    @Transactional public void success(UUID id){db.update("UPDATE integration_job_executions SET status='SUCCEEDED',finished_at=now() WHERE tenant_id=? AND id=?",TenantContext.getTenantId(),id);}
    @Transactional public void failure(UUID id,String error){db.update("UPDATE integration_job_executions SET status='FAILED',finished_at=now(),error_detail=? WHERE tenant_id=? AND id=?",error,TenantContext.getTenantId(),id);}
    public Map<String,Object> latest(String jobName){return db.queryForMap("SELECT * FROM integration_job_executions WHERE tenant_id=? AND job_name=? ORDER BY started_at DESC LIMIT 1",TenantContext.getTenantId(),jobName);}
}
