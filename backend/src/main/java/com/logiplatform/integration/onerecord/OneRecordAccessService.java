package com.logiplatform.integration.onerecord;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class OneRecordAccessService {
    private final JdbcTemplate db;
    public OneRecordAccessService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.db=db;}
    @Transactional public void grant(String objectReference,String grantee,String permission,Instant expiresAt){db.update("INSERT INTO onerecord_access_grants(id,tenant_id,object_reference,grantee,permission,expires_at) VALUES(gen_random_uuid(),?,?,?,?,?)",TenantContext.getTenantId(),objectReference,grantee,permission,expiresAt);}
    @Transactional public void revoke(UUID id){db.update("UPDATE onerecord_access_grants SET revoked_at=now() WHERE tenant_id=? AND id=?",TenantContext.getTenantId(),id);}
    public boolean allowed(String objectReference,String grantee,String permission){return !db.queryForList("SELECT id FROM onerecord_access_grants WHERE tenant_id=? AND object_reference=? AND grantee=? AND permission=? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at>now()) LIMIT 1",TenantContext.getTenantId(),objectReference,grantee,permission).isEmpty();}
}
