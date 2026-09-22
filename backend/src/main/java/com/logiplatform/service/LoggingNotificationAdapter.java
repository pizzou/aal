package com.logiplatform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Active whenever notifications.smtp.enabled is not set to true (the default). Sends
 * nothing — NotificationService still records every attempt to the notifications
 * table regardless of which adapter handled it, so switching between this and
 * SmtpNotificationAdapter never changes what's auditable, only whether mail
 * actually leaves the building.
 */
@Component
@ConditionalOnProperty(name = "notifications.smtp.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingNotificationAdapter implements NotificationSenderPort {

    @Override
    public NotificationResult send(String recipientEmail, String subject, String body) {
        return new NotificationResult(false, null);
    }
}
