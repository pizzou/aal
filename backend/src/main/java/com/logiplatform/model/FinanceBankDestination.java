package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_bank_destinations",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_finance_bank_destination_code",
           columnNames = {"tenant_id", "code"}))
public class FinanceBankDestination {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "account_reference", length = 160)
    private String accountReference;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FinanceBankDestination() {}

    public FinanceBankDestination(UUID tenantId, String code, String name,
                                  String accountReference, String description) {
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.accountReference = accountReference;
        this.description = description;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getAccountReference() { return accountReference; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void update(String code, String name, String accountReference,
                       String description, boolean active) {
        this.code = code;
        this.name = name;
        this.accountReference = accountReference;
        this.description = description;
        this.active = active;
    }
}
