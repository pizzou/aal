package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "license_number", nullable = false)
    private String licenseNumber;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DriverStatus status = DriverStatus.AVAILABLE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Driver() {}

    public Driver(UUID tenantId, String fullName, String licenseNumber, String phone) {
        this.tenantId = tenantId;
        this.fullName = fullName;
        this.licenseNumber = licenseNumber;
        this.phone = phone;
    }

    public void markOnTrip() {
        if (this.status != DriverStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Driver must be AVAILABLE to start a trip (current status: " + this.status + ")");
        }
        this.status = DriverStatus.ON_TRIP;
    }

    public void markAvailable() {
        this.status = DriverStatus.AVAILABLE;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getFullName() { return fullName; }
    public String getLicenseNumber() { return licenseNumber; }
    public String getPhone() { return phone; }
    public DriverStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
