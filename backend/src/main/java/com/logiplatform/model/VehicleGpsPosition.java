package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_gps_positions")
public class VehicleGpsPosition {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id", nullable=false, updatable=false) private UUID tenantId;
    @Column(name="vehicle_id", nullable=false, updatable=false) private UUID vehicleId;
    @Column(nullable=false, updatable=false) private double latitude;
    @Column(nullable=false, updatable=false) private double longitude;
    @Column(name="speed_kmh", updatable=false) private Double speedKmh;
    @Column(name="heading_degrees", updatable=false) private Double headingDegrees;
    @Column(name="recorded_at", nullable=false, updatable=false) private Instant recordedAt;
    @Column(name="source", nullable=false, updatable=false) private String source = "MANUAL";
    @Column(name="device_id", updatable=false) private String deviceId;
    @Column(name="accuracy_meters", updatable=false) private Double accuracyMeters;
    @Column(name="battery_percent", updatable=false) private Double batteryPercent;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt = Instant.now();

    protected VehicleGpsPosition() {}

    public VehicleGpsPosition(UUID tenantId, UUID vehicleId, double latitude, double longitude,
                              Double speedKmh, Double headingDegrees, Instant recordedAt) {
        this(tenantId, vehicleId, latitude, longitude, speedKmh, headingDegrees, recordedAt, "MANUAL", null, null, null);
    }

    public VehicleGpsPosition(UUID tenantId, UUID vehicleId, double latitude, double longitude,
                              Double speedKmh, Double headingDegrees, Instant recordedAt,
                              String source, String deviceId, Double accuracyMeters, Double batteryPercent) {
        if (latitude < -90 || latitude > 90) throw new IllegalArgumentException("latitude must be between -90 and 90, got " + latitude);
        if (longitude < -180 || longitude > 180) throw new IllegalArgumentException("longitude must be between -180 and 180, got " + longitude);
        if (headingDegrees != null && (headingDegrees < 0 || headingDegrees >= 360)) throw new IllegalArgumentException("headingDegrees must be between 0 and <360");
        if (speedKmh != null && speedKmh < 0) throw new IllegalArgumentException("speedKmh cannot be negative");
        if (accuracyMeters != null && accuracyMeters < 0) throw new IllegalArgumentException("accuracyMeters cannot be negative");
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100)) throw new IllegalArgumentException("batteryPercent must be between 0 and 100");
        this.tenantId=tenantId; this.vehicleId=vehicleId; this.latitude=latitude; this.longitude=longitude;
        this.speedKmh=speedKmh; this.headingDegrees=headingDegrees; this.recordedAt=recordedAt;
        this.source=(source==null||source.isBlank()?"MANUAL":source.trim().toUpperCase());
        this.deviceId=deviceId==null||deviceId.isBlank()?null:deviceId.trim();
        this.accuracyMeters=accuracyMeters; this.batteryPercent=batteryPercent;
    }

    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getVehicleId(){return vehicleId;}
    public double getLatitude(){return latitude;} public double getLongitude(){return longitude;} public Double getSpeedKmh(){return speedKmh;}
    public Double getHeadingDegrees(){return headingDegrees;} public Instant getRecordedAt(){return recordedAt;} public String getSource(){return source;}
    public String getDeviceId(){return deviceId;} public Double getAccuracyMeters(){return accuracyMeters;} public Double getBatteryPercent(){return batteryPercent;}
    public Instant getCreatedAt(){return createdAt;}
}
