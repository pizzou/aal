package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "registration_number", nullable = false)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false)
    private VehicleType vehicleType;

    @Column(name = "capacity_kg", nullable = false)
    private int capacityKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Vehicle() {}

    public Vehicle(UUID tenantId, String registrationNumber, VehicleType vehicleType, int capacityKg) {
        this.tenantId = tenantId;
        this.registrationNumber = registrationNumber;
        this.vehicleType = vehicleType;
        this.capacityKg = capacityKg;
    }

    public void markOnTrip() {
        requireStatus(VehicleStatus.AVAILABLE, "Vehicle must be AVAILABLE to start a trip");
        this.status = VehicleStatus.ON_TRIP;
    }

    public void markAvailable() {
        this.status = VehicleStatus.AVAILABLE;
    }

    private void requireStatus(VehicleStatus required, String message) {
        if (this.status != required) {
            throw new IllegalStateException(message + " (current status: " + this.status + ")");
        }
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getRegistrationNumber() { return registrationNumber; }
    public VehicleType getVehicleType() { return vehicleType; }
    public int getCapacityKg() { return capacityKg; }
    public VehicleStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
