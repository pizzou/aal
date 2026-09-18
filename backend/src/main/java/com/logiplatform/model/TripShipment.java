package com.logiplatform.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "trip_shipments")
public class TripShipment {

    @EmbeddedId
    private TripShipmentId id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected TripShipment() {}

    public TripShipment(UUID tenantId, UUID tripId, UUID shipmentId) {
        this.tenantId = tenantId;
        this.id = new TripShipmentId(tripId, shipmentId);
    }

    public TripShipmentId getId() { return id; }
    public UUID getTenantId() { return tenantId; }
}
