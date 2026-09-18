package com.logiplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "dispatch_stops",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dispatch_stop_seq",
                columnNames = {"tenant_id", "trip_id", "sequence_no"}
        )
)
public class DispatchStop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "stop_type", nullable = false)
    private String stopType;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "geofence_radius_m")
    private Integer geofenceRadiusM = 500;

    @Column(name = "planned_at")
    private Instant plannedAt;

    @Column(name = "eta")
    private Instant eta;

    @Column(name = "actual_at")
    private Instant actualAt;

    @Column(name = "status", nullable = false)
    private String status = "PLANNED";

    /**
     * Distance from the previous dispatch stop in kilometres.
     *
     * BigDecimal is used intentionally for deterministic decimal
     * persistence instead of SQL floating-point semantics.
     */
    @Column(
            name = "distance_from_previous_km",
            precision = 12,
            scale = 3
    )
    private BigDecimal distanceFromPreviousKm;

    @Column(name = "planned_duration_minutes")
    private Integer plannedDurationMinutes;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DispatchStop() {
    }

    public DispatchStop(
            UUID tenantId,
            UUID tripId,
            UUID shipmentId,
            int sequenceNo,
            String stopType,
            String address,
            Double latitude,
            Double longitude,
            Instant plannedAt,
            Instant eta,
            BigDecimal distanceFromPreviousKm,
            Integer plannedDurationMinutes,
            String notes
    ) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.shipmentId = shipmentId;
        this.sequenceNo = sequenceNo;
        this.stopType = stopType;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.plannedAt = plannedAt;
        this.eta = eta;
        this.distanceFromPreviousKm = distanceFromPreviousKm;
        this.plannedDurationMinutes = plannedDurationMinutes;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public String getStopType() {
        return stopType;
    }

    public String getAddress() {
        return address;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Integer getGeofenceRadiusM() {
        return geofenceRadiusM;
    }

    public Instant getPlannedAt() {
        return plannedAt;
    }

    public Instant getEta() {
        return eta;
    }

    public Instant getActualAt() {
        return actualAt;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getDistanceFromPreviousKm() {
        return distanceFromPreviousKm;
    }

    public Integer getPlannedDurationMinutes() {
        return plannedDurationMinutes;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void arrive(Instant at) {
        actualAt = at == null ? Instant.now() : at;
        status = "ARRIVED";
    }

    public void depart(Instant at) {
        actualAt = at == null ? Instant.now() : at;
        status = "COMPLETED";
    }

    public void skip(String reason) {
        status = "SKIPPED";
        notes = reason;
    }

    public void updateEta(Instant value) {
        eta = value;
    }
}

