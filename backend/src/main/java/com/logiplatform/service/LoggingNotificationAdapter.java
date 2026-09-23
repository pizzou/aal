package com.logiplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Safe default notification sender. It guarantees the notification subsystem has
 * a concrete NotificationSenderPort when SMTP is deliberately disabled.
 */
@Component
@ConditionalOnProperty(
        name = "notifications.smtp.enabled",
        havingValue = "false",
        matchIfMissing = true)
public final class LoggingNotificationAdapter implements NotificationSenderPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public NotificationResult send(String recipientEmail, String subject, String body) {
        log.info("Notification logged only recipient={} subject={} bodyLength={}",
                recipientEmail,
                subject,
                body == null ? 0 : body.length());
        return new NotificationResult(false, null);
    }
}
