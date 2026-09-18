package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_gps_positions")
public class VehicleGpsPosition {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(nullable = false, updatable = false)
    private double latitude;

    @Column(nullable = false, updatable = false)
    private double longitude;

    @Column(name = "speed_kmh", updatable = false)
    private Double speedKmh;

    @Column(name = "heading_degrees", updatable = false)
    private Double headingDegrees;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected VehicleGpsPosition() {}

    public VehicleGpsPosition(UUID tenantId, UUID vehicleId, double latitude, double longitude,
                               Double speedKmh, Double headingDegrees, Instant recordedAt) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be between -90 and 90, got " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be between -180 and 180, got " + longitude);
        }
        this.tenantId = tenantId;
        this.vehicleId = vehicleId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedKmh = speedKmh;
        this.headingDegrees = headingDegrees;
        this.recordedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public UUID getVehicleId() { return vehicleId; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public Double getSpeedKmh() { return speedKmh; }
    public Double getHeadingDegrees() { return headingDegrees; }
    public Instant getRecordedAt() { return recordedAt; }
}
