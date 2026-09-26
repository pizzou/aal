package com.logiplatform.service.control;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class IntegrationCredentialLifecycleJob {
    private final JdbcTemplate db; private final DistributedJobLockService locks; private final UUID tenantId; private final int warningDays;
    public IntegrationCredentialLifecycleJob(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,DistributedJobLockService locks,@Value("${app.single-tenant.id}") UUID tenantId,@Value("${integration.credentials.expiry-warning-days:30}") int warningDays){this.db=db;this.locks=locks;this.tenantId=tenantId;this.warningDays=Math.max(1,warningDays);}
    @Scheduled(fixedDelayString="${integration.credentials.lifecycle-interval-ms:3600000}")
    public void run(){TenantContext.setTenantId(tenantId);String owner=locks.tryAcquire("integration-credential-lifecycle",Duration.ofMinutes(10));if(owner==null){TenantContext.clear();return;}try{db.update("UPDATE integration_credential_versions SET status='EXPIRED' WHERE tenant_id=? AND status='ACTIVE' AND expires_at IS NOT NULL AND expires_at<=now()",tenantId);db.update("UPDATE integration_accounts a SET status='CREDENTIAL_EXPIRING',updated_at=now() WHERE a.tenant_id=? AND EXISTS(SELECT 1 FROM integration_credential_versions c WHERE c.account_id=a.id AND c.tenant_id=a.tenant_id AND c.status='ACTIVE' AND c.expires_at IS NOT NULL AND c.expires_at<=now()+(? || ' days')::interval)",tenantId,warningDays);}finally{locks.release("integration-credential-lifecycle",owner);TenantContext.clear();}}
}
