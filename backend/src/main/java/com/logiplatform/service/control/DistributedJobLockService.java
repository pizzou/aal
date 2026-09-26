package com.logiplatform.service.control;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.UUID;

@Service
public class DistributedJobLockService {
    private final JdbcTemplate db;
    public DistributedJobLockService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.db=db;}
    public String tryAcquire(String jobName,Duration lease){UUID tenant=TenantContext.getTenantId();String owner=UUID.randomUUID().toString();int updated=db.update("INSERT INTO integration_job_locks(tenant_id,job_name,locked_until,owner_id) VALUES(?,?,now() + (? || ' milliseconds')::interval,?) ON CONFLICT(tenant_id,job_name) DO UPDATE SET locked_until=EXCLUDED.locked_until,owner_id=EXCLUDED.owner_id,updated_at=now() WHERE integration_job_locks.locked_until<=now()",tenant,jobName,Math.max(1000,lease.toMillis()),owner);return updated==1?owner:null;}
    public void release(String jobName,String owner){if(owner==null)return;db.update("UPDATE integration_job_locks SET locked_until=now(),updated_at=now() WHERE tenant_id=? AND job_name=? AND owner_id=?",TenantContext.getTenantId(),jobName,owner);}
}
