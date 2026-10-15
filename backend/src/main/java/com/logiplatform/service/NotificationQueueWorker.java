package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import com.logiplatform.service.control.DistributedJobLockService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class NotificationQueueWorker {
    private final JdbcTemplate db;
    private final NotificationSenderPort sender;
    private final DistributedJobLockService jobLocks;
    private final UUID tenantId;

    public NotificationQueueWorker(@Qualifier("tenantJdbcTemplate") JdbcTemplate db, NotificationSenderPort sender,
            DistributedJobLockService jobLocks,
            @Value("${app.single-tenant.id}") UUID tenantId) {
        this.db = db;
        this.sender = sender;
        this.jobLocks = jobLocks;
        this.tenantId = tenantId;
    }

    @Scheduled(fixedDelayString = "${notifications.queue.poll-ms:5000}")
    public void drain() {
        String owner = null;
        TenantContext.setTenantId(tenantId);
        try {
            owner = jobLocks.tryAcquire("notification-queue", Duration.ofSeconds(20));
            if (owner == null)
                return;

            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT id,shipment_id,recipient,subject,body,attempts FROM notification_queue " +
                            "WHERE tenant_id=? AND status IN ('QUEUED','FAILED') AND next_attempt_at<=now() " +
                            "AND attempts<8 ORDER BY created_at LIMIT 25",
                    tenantId);
            for (Map<String, Object> row : rows) {
                UUID id = (UUID) row.get("id");
                String recipient = (String) row.get("recipient");
                try {
                    if (recipient == null || recipient.isBlank()) {
                        failAndScheduleRetry(id, "Recipient missing");
                        continue;
                    }
                    NotificationSenderPort.NotificationResult result = sender.send(
                            recipient, (String) row.get("subject"), (String) row.get("body"));
                    if (result != null && result.sent()) {
                        db.update("UPDATE notification_queue SET status='SENT',attempts=attempts+1," +
                                "sent_at=now(),last_error=NULL WHERE id=? AND tenant_id=? AND status IN ('QUEUED','FAILED')",
                                id, tenantId);
                    } else {
                        String errorMessage = result == null || result.errorDetail() == null
                                ? "Delivery adapter did not confirm send"
                                : safeError(result.errorDetail());
                        failAndScheduleRetry(id, errorMessage);
                    }
                } catch (Exception deliveryFailure) {
                    // One bad provider call must not abort the rest of the queue. Avoid storing
                    // exception text that may contain recipient data, credentials or request
                    // bodies.
                    failAndScheduleRetry(id, "Notification delivery attempt failed ("
                            + deliveryFailure.getClass().getSimpleName() + ")");
                }
            }
        } finally {
            if (owner != null) {
                try {
                    jobLocks.release("notification-queue", owner);
                } catch (Exception ignored) {
                    /* Lease expiry is the final recovery path. */ }
            }
            TenantContext.clear();
        }
    }

    private void failAndScheduleRetry(UUID id, String message) {
        db.update(
                "UPDATE notification_queue SET status=CASE WHEN attempts+1>=8 THEN 'DEAD_LETTER' ELSE 'FAILED' END,attempts=attempts+1,last_error=?,"
                        +
                        "next_attempt_at=now() + (interval '1 minute' * power(2,LEAST(attempts,6))) " +
                        "WHERE id=? AND tenant_id=? AND status IN ('QUEUED','FAILED')",
                safeError(message), id, tenantId);
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank())
            return "Delivery failed";
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return cleaned.length() > 240 ? cleaned.substring(0, 240) : cleaned;
    }

}
