package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    @Column(name = "security_surcharge_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal securitySurchargePercent = BigDecimal.ZERO;
    @Column(name = "markup_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal markupPercent = BigDecimal.ZERO;
    @Column(name = "currency", nullable = false)
    private String currency = "USD";
    @Column(name = "lane_code")
    private String laneCode;
    @Column(name = "client_id")
    private UUID clientId;
    @Column(name = "carrier_id")
    private UUID carrierId;
    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom = LocalDate.now();
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "active", nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected RateCard() {}

    public RateCard(UUID tenantId, String transportMode, BigDecimal baseRatePerKg,
                     BigDecimal minCharge, BigDecimal fuelSurchargePercent, String currency) {
        this(tenantId, transportMode, baseRatePerKg, minCharge, fuelSurchargePercent, currency,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, LocalDate.now(), null);
    }

    public RateCard(UUID tenantId, String transportMode, BigDecimal baseRatePerKg,
                     BigDecimal minCharge, BigDecimal fuelSurchargePercent, String currency,
                     BigDecimal securitySurchargePercent, BigDecimal markupPercent, String laneCode,
                     UUID clientId, UUID carrierId, LocalDate validFrom, LocalDate validUntil) {
        this.tenantId = tenantId;
        this.transportMode = transportMode;
        this.baseRatePerKg = baseRatePerKg;
        this.minCharge = minCharge;
        this.fuelSurchargePercent = fuelSurchargePercent;
        this.securitySurchargePercent = securitySurchargePercent == null ? BigDecimal.ZERO : securitySurchargePercent;
        this.markupPercent = markupPercent == null ? BigDecimal.ZERO : markupPercent;
        this.currency = currency == null ? "USD" : currency;
        this.laneCode = laneCode;
        this.clientId = clientId;
        this.carrierId = carrierId;
        this.validFrom = validFrom == null ? LocalDate.now() : validFrom;
        this.validUntil = validUntil;
        this.active = true;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getTransportMode() { return transportMode; }
    public BigDecimal getBaseRatePerKg() { return baseRatePerKg; }
    public BigDecimal getMinCharge() { return minCharge; }
    public BigDecimal getFuelSurchargePercent() { return fuelSurchargePercent; }
    public BigDecimal getSecuritySurchargePercent() { return securitySurchargePercent; }
    public BigDecimal getMarkupPercent() { return markupPercent; }
    public String getCurrency() { return currency; }
    public String getLaneCode() { return laneCode; }
    public UUID getClientId() { return clientId; }
    public UUID getCarrierId() { return carrierId; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }
}
