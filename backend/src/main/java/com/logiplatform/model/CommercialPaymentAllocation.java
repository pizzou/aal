package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "commercial_payment_allocations",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_commercial_payment_allocation",
           columnNames = {"payment_id", "bank_destination_id"}))
public class CommercialPaymentAllocation {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "bank_destination_id", nullable = false, updatable = false)
    private UUID bankDestinationId;

    @Column(name = "percentage", nullable = false, precision = 7, scale = 4)
    private java.math.BigDecimal percentage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CommercialPaymentAllocation() {}

    public CommercialPaymentAllocation(UUID tenantId, UUID paymentId,
                                       UUID bankDestinationId,
                                       java.math.BigDecimal percentage) {
        this.tenantId = tenantId;
        this.paymentId = paymentId;
        this.bankDestinationId = bankDestinationId;
        this.percentage = percentage;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getBankDestinationId() { return bankDestinationId; }
    public java.math.BigDecimal getPercentage() { return percentage; }
    public Instant getCreatedAt() { return createdAt; }
}
