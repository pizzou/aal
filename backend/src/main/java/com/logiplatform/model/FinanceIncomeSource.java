package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_income_sources",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_finance_income_source_code",
           columnNames = {"tenant_id", "code"}))
public class FinanceIncomeSource {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FinanceIncomeSource() {}

    public FinanceIncomeSource(UUID tenantId, String code, String name, String description) {
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void update(String code, String name, String description, boolean active) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.active = active;
    }
}
