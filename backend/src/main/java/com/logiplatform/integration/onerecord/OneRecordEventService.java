package com.logiplatform.integration.onerecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OneRecordEventService {
    private final JdbcTemplate db;
    public OneRecordEventService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.db=db;}
    @Transactional public boolean receive(String eventId,String objectReference,String eventType,JsonNode payload){int inserted=db.update("INSERT INTO onerecord_events(id,tenant_id,event_id,object_reference,event_type,api_version,payload_jsonld) VALUES(gen_random_uuid(),?,?,?,?,?,?) ON CONFLICT DO NOTHING",TenantContext.getTenantId(),eventId,objectReference,eventType,payload.path("apiVersion").asText("unknown"),payload.toString());return inserted>0;}
    public List<Map<String,Object>> recent(int limit){return db.queryForList("SELECT event_id,object_reference,event_type,api_version,status,created_at,processed_at FROM onerecord_events WHERE tenant_id=? ORDER BY created_at DESC LIMIT ?",TenantContext.getTenantId(),Math.min(Math.max(limit,1),200));}
}
