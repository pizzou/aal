package com.logiplatform.integration.onerecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class OneRecordObjectService {
    private final JdbcTemplate db; private final OneRecordClient client; private final String ontologyVersion;
    public OneRecordObjectService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,OneRecordClient client,@Value("${onerecord.ontology-version:3.3.0}") String ontologyVersion){this.db=db;this.client=client;this.ontologyVersion=ontologyVersion;}
    @Transactional public Map<String,Object> upsert(String objectReference,String objectType,JsonNode jsonLd){UUID tenant=TenantContext.getTenantId();String payload=jsonLd.toString();db.update("INSERT INTO onerecord_objects(id,tenant_id,object_reference,object_type,api_version,ontology_version,json_ld) VALUES(gen_random_uuid(),?,?,?,?,?,?) ON CONFLICT(tenant_id,object_reference) DO UPDATE SET object_type=EXCLUDED.object_type,api_version=EXCLUDED.api_version,ontology_version=EXCLUDED.ontology_version,json_ld=EXCLUDED.json_ld,updated_at=now()",tenant,objectReference,objectType,client.apiVersion(),ontologyVersion,payload);return db.queryForMap("SELECT object_reference,object_type,api_version,ontology_version,updated_at FROM onerecord_objects WHERE tenant_id=? AND object_reference=?",tenant,objectReference);}
    public JsonNode fetch(String objectReference){return client.get(objectReference);}
}
