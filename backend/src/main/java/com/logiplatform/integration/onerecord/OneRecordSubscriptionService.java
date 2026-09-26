package com.logiplatform.integration.onerecord;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OneRecordSubscriptionService {
    private final JdbcTemplate db; private final OneRecordClient client;
    public OneRecordSubscriptionService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,OneRecordClient client){this.db=db;this.client=client;}
    @Transactional public Map<String,Object> subscribe(String reference,String objectType,String callbackUrl){UUID tenant=TenantContext.getTenantId();String body="{\"objectType\":\""+escape(objectType)+"\",\"callbackUrl\":\""+escape(callbackUrl)+"\"}";var remote=client.post("/subscriptions",body);String providerId=remote.path("id").asText(null);db.update("INSERT INTO onerecord_subscriptions(id,tenant_id,subscription_reference,object_type,callback_url,status,provider_subscription_id) VALUES(gen_random_uuid(),?,?,?,?, 'ACTIVE',?) ON CONFLICT(tenant_id,subscription_reference) DO UPDATE SET object_type=EXCLUDED.object_type,callback_url=EXCLUDED.callback_url,status='ACTIVE',provider_subscription_id=EXCLUDED.provider_subscription_id,updated_at=now()",tenant,reference,objectType,callbackUrl,providerId);return db.queryForMap("SELECT * FROM onerecord_subscriptions WHERE tenant_id=? AND subscription_reference=?",tenant,reference);}
    private static String escape(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"");}
}
