package com.logiplatform.model;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id, String recipient, String subject, String status, String errorDetail, Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getRecipient(), n.getSubject(),
                n.getStatus(), n.getErrorDetail(), n.getCreatedAt());
    }
}
