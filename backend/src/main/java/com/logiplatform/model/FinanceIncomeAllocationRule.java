package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_income_allocation_rules",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_finance_income_allocation_rule",
           columnNames = {"tenant_id", "income_source_id", "bank_destination_id"}))
public class FinanceIncomeAllocationRule {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "income_source_id", nullable = false, updatable = false)
    private UUID incomeSourceId;

    @Column(name = "bank_destination_id", nullable = false, updatable = false)
    private UUID bankDestinationId;

    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal percentage;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FinanceIncomeAllocationRule() {}

    public FinanceIncomeAllocationRule(UUID tenantId, UUID incomeSourceId,
                                       UUID bankDestinationId, BigDecimal percentage) {
        this.tenantId = tenantId;
        this.incomeSourceId = incomeSourceId;
        this.bankDestinationId = bankDestinationId;
        this.percentage = percentage;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getIncomeSourceId() { return incomeSourceId; }
    public UUID getBankDestinationId() { return bankDestinationId; }
    public BigDecimal getPercentage() { return percentage; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void update(BigDecimal percentage, boolean active) {
        this.percentage = percentage;
        this.active = active;
    }
}
