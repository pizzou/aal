package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(nullable = false, updatable = false)
    private String channel = "EMAIL";

    @Column(updatable = false)
    private String recipient;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(nullable = false, updatable = false)
    private String status; // SENT, LOGGED_ONLY, FAILED

    @Column(name = "error_detail", updatable = false)
    private String errorDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {}

    public Notification(UUID tenantId, UUID shipmentId, String recipient, String subject,
                         String body, String status, String errorDetail) {
        this.tenantId = tenantId;
        this.shipmentId = shipmentId;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.status = status;
        this.errorDetail = errorDetail;
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getStatus() { return status; }
    public String getErrorDetail() { return errorDetail; }
    public Instant getCreatedAt() { return createdAt; }
}
