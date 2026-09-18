package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rate_cards")
public class RateCard {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "transport_mode", nullable = false)
    private String transportMode;

    @Column(name = "base_rate_per_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseRatePerKg;

    @Column(name = "min_charge", nullable = false, precision = 10, scale = 2)
    private BigDecimal minCharge;

    @Column(name = "fuel_surcharge_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal fuelSurchargePercent;

    @Column(nullable = false)
    private String currency = "USD";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RateCard() {}

    public RateCard(UUID tenantId, String transportMode, BigDecimal baseRatePerKg,
                     BigDecimal minCharge, BigDecimal fuelSurchargePercent, String currency) {
        this.tenantId = tenantId;
        this.transportMode = transportMode;
        this.baseRatePerKg = baseRatePerKg;
        this.minCharge = minCharge;
        this.fuelSurchargePercent = fuelSurchargePercent;
        this.currency = currency != null ? currency : "USD";
    }

    public UUID getId() { return id; }
    public String getTransportMode() { return transportMode; }
    public BigDecimal getBaseRatePerKg() { return baseRatePerKg; }
    public BigDecimal getMinCharge() { return minCharge; }
    public BigDecimal getFuelSurchargePercent() { return fuelSurchargePercent; }
    public String getCurrency() { return currency; }
    public Instant getUpdatedAt() { return updatedAt; }
}
