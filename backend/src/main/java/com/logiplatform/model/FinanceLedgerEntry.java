package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_ledger_entries")
public class FinanceLedgerEntry {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "entry_type", nullable = false)
    private String entryType;

    @Column(name = "account_code", nullable = false)
    private String accountCode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "description")
    private String description;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt = Instant.now();

    protected FinanceLedgerEntry() {
    }

    public FinanceLedgerEntry(UUID tenantId, UUID invoiceId, String entryType,
            String accountCode, BigDecimal amount, String currency,
            String description) {
        this(tenantId, invoiceId, null, null, entryType, accountCode, amount, currency, description);
    }

    public FinanceLedgerEntry(UUID tenantId, UUID invoiceId, String sourceType, UUID sourceId,
            String entryType, String accountCode, BigDecimal amount,
            String currency, String description) {
        this.tenantId = tenantId;
        this.invoiceId = invoiceId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.entryType = entryType;
        this.accountCode = accountCode;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public String getEntryType() {
        return entryType;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
