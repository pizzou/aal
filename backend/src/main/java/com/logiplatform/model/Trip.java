package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(name = "driver_id", nullable = false, updatable = false)
    private UUID driverId;

    @Column(name = "origin_address", nullable = false)
    private String originAddress;

    @Column(name = "destination_address", nullable = false)
    private String destinationAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripStatus status = TripStatus.PLANNED;

    @Column(name = "scheduled_departure")
    private Instant scheduledDeparture;

    @Column(name = "actual_departure")
    private Instant actualDeparture;

    @Column(name = "actual_arrival")
    private Instant actualArrival;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Trip() {}

    public Trip(UUID tenantId, UUID vehicleId, UUID driverId, String originAddress,
                String destinationAddress, Instant scheduledDeparture) {
        this.tenantId = tenantId;
        this.vehicleId = vehicleId;
        this.driverId = driverId;
        this.originAddress = originAddress;
        this.destinationAddress = destinationAddress;
        this.scheduledDeparture = scheduledDeparture;
    }

    public void start() {
        requireStatus(TripStatus.PLANNED);
        this.status = TripStatus.IN_PROGRESS;
        this.actualDeparture = Instant.now();
        touch();
    }

    public void complete() {
        requireStatus(TripStatus.IN_PROGRESS);
        this.status = TripStatus.COMPLETED;
        this.actualArrival = Instant.now();
        touch();
    }

    public void cancel() {
        if (this.status == TripStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a trip that has already completed");
        }
        this.status = TripStatus.CANCELLED;
        touch();
    }

    private void requireStatus(TripStatus required) {
        if (this.status != required) {
            throw new IllegalStateException(
                    "Trip must be " + required + " for this transition (current: " + this.status + ")");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getDriverId() { return driverId; }
    public String getOriginAddress() { return originAddress; }
    public String getDestinationAddress() { return destinationAddress; }
    public TripStatus getStatus() { return status; }
    public Instant getScheduledDeparture() { return scheduledDeparture; }
    public Instant getActualDeparture() { return actualDeparture; }
    public Instant getActualArrival() { return actualArrival; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
