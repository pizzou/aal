package com.logiplatform.service;

import com.logiplatform.model.Notification;
import com.logiplatform.repository.NotificationRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Every call to notify() writes exactly one audit row, regardless of which
 * NotificationSenderPort implementation is active:
 *  - no recipient email on file -> status LOGGED_ONLY, recipient null, no send attempted
 *  - LoggingNotificationAdapter active (default) -> status LOGGED_ONLY
 *  - SmtpNotificationAdapter active and send succeeds -> status SENT
 *  - SmtpNotificationAdapter active and send fails -> status FAILED, error captured
 *
 * This means the notifications table is always a complete, trustworthy record of
 * "what happened" even in an environment with no real email configured — which is
 * every environment until someone deliberately sets notifications.smtp.enabled=true.
 */
@Service
public class NotificationService {

    private final NotificationSenderPort senderPort;
    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationSenderPort senderPort, NotificationRepository notificationRepository) {
        this.senderPort = senderPort;
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public void notify(UUID shipmentId, String recipientEmail, String subject, String body) {
        UUID tenantId = TenantContext.getTenantId();

        if (recipientEmail == null || recipientEmail.isBlank()) {
            notificationRepository.save(new Notification(
                    tenantId, shipmentId, null, subject, body, "LOGGED_ONLY",
                    "No notification email configured for this shipment"));
            return;
        }

        NotificationSenderPort.NotificationResult result = senderPort.send(recipientEmail, subject, body);

        String status = result.sent() ? "SENT" : (result.errorDetail() != null ? "FAILED" : "LOGGED_ONLY");
        notificationRepository.save(new Notification(
                tenantId, shipmentId, recipientEmail, subject, body, status, result.errorDetail()));
    }

    @Transactional(readOnly = true)
    public Page<Notification> history(UUID shipmentId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return notificationRepository.findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(tenantId, shipmentId, pageable);
    }
}
