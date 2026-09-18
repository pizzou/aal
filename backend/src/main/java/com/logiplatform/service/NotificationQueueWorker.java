package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class NotificationQueueWorker {
    private final JdbcTemplate db; private final NotificationSenderPort sender; private final UUID tenantId;
    public NotificationQueueWorker(@Qualifier("tenantJdbcTemplate") JdbcTemplate db, NotificationSenderPort sender,
            @Value("${app.single-tenant.id}") UUID tenantId){this.db=db;this.sender=sender;this.tenantId=tenantId;}
    @Scheduled(fixedDelayString="${notifications.queue.poll-ms:5000}")
    public void drain(){
        TenantContext.setTenantId(tenantId);
        try {
            List<Map<String,Object>> rows=db.queryForList("SELECT id,shipment_id,recipient,subject,body,attempts FROM notification_queue WHERE tenant_id=? AND status IN ('QUEUED','FAILED') AND next_attempt_at<=now() AND attempts<8 ORDER BY created_at LIMIT 25",tenantId);
            for(Map<String,Object> r:rows){
                UUID id=(UUID)r.get("id"); String recipient=(String)r.get("recipient");
                if(recipient==null||recipient.isBlank()){db.update("UPDATE notification_queue SET status='FAILED',attempts=attempts+1,last_error='Recipient missing',next_attempt_at=now()+interval '1 hour' WHERE id=? AND tenant_id=?",id,tenantId);continue;}
                NotificationSenderPort.NotificationResult result=sender.send(recipient,(String)r.get("subject"),(String)r.get("body"));
                if(result.sent()) db.update("UPDATE notification_queue SET status='SENT',attempts=attempts+1,sent_at=now(),last_error=NULL WHERE id=? AND tenant_id=?",id,tenantId);
                else db.update("UPDATE notification_queue SET status='FAILED',attempts=attempts+1,last_error=?,next_attempt_at=now() + (interval '1 minute' * power(2,LEAST(attempts,6))) WHERE id=? AND tenant_id=?",result.errorDetail()==null?"Delivery adapter did not send":result.errorDetail(),id,tenantId);
            }
        } finally { TenantContext.clear(); }
    }
}
