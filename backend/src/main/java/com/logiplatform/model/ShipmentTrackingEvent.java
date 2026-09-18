package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_tracking_events")
public class ShipmentTrackingEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private TrackingEventType eventType;

    @Column(updatable = false)
    private String location;

    @Column(updatable = false)
    private String notes;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ShipmentTrackingEvent() {}

    public ShipmentTrackingEvent(UUID tenantId, UUID shipmentId, TrackingEventType eventType,
                                  String location, String notes, Instant occurredAt) {
        this.tenantId = tenantId;
        this.shipmentId = shipmentId;
        this.eventType = eventType;
        this.location = location;
        this.notes = notes;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public TrackingEventType getEventType() { return eventType; }
    public String getLocation() { return location; }
    public String getNotes() { return notes; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
