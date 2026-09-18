package com.logiplatform.model;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class TripShipmentId implements Serializable {

    private UUID tripId;
    private UUID shipmentId;

    protected TripShipmentId() {}

    public TripShipmentId(UUID tripId, UUID shipmentId) {
        this.tripId = tripId;
        this.shipmentId = shipmentId;
    }

    public UUID getTripId() { return tripId; }
    public UUID getShipmentId() { return shipmentId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TripShipmentId that)) return false;
        return Objects.equals(tripId, that.tripId) && Objects.equals(shipmentId, that.shipmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tripId, shipmentId);
    }
}
