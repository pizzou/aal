package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AirlineDeadLetterService {
    private final JdbcTemplate db;
    public AirlineDeadLetterService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.db=db;}
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID enqueue(String provider,String operation,String key,String correlation,String payload,String error){
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO airline_integration_dead_letters(id,tenant_id,provider,operation,idempotency_key,correlation_id,payload,error_detail) VALUES(?,?,?,?,?,?,?,?)",id,TenantContext.getTenantId(),provider,operation,key,correlation,payload,error==null?"integration failed":error);
        return id;
    }
    public List<Map<String,Object>> list(){return db.queryForList("SELECT id,provider,operation,idempotency_key,correlation_id,error_detail,attempts,status,last_attempt_at,created_at FROM airline_integration_dead_letters WHERE tenant_id=? ORDER BY created_at DESC",TenantContext.getTenantId());}
}
