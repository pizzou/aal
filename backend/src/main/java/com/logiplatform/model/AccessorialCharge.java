package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "accessorial_charges")
public class AccessorialCharge {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "USD";

    protected AccessorialCharge() {}

    public AccessorialCharge(UUID tenantId, String code, String description, BigDecimal amount, String currency) {
        this.tenantId = tenantId;
        this.code = code;
        this.description = description;
        this.amount = amount;
        this.currency = currency != null ? currency : "USD";
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
