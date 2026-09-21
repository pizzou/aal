package com.logiplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "commercial_quotes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_quote_ref",
                columnNames = {"tenant_id", "quote_id"}
        )
)
public class CommercialQuote {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "quote_id", nullable = false)
    private String quoteId;

    @Column(name = "quote_date", nullable = false)
    private LocalDate quoteDate;

    @Column(name = "client")
    private String client;

    @Column(name = "route")
    private String route;

    @Column(name = "service_type")
    private String serviceType;

    @Column(name = "commodity")
    private String commodity;

    @Column(name = "chargeable_weight_kg", precision = 18, scale = 3)
    private BigDecimal chargeableWeightKg;

    @Column(name = "supplier_cost", precision = 19, scale = 4)
    private BigDecimal supplierCost;

    @Column(name = "other_cost", precision = 19, scale = 4)
    private BigDecimal otherCost;

    @Column(name = "markup_percent", precision = 9, scale = 4)
    private BigDecimal markupPercent;

    @Column(name = "quoted_amount", precision = 19, scale = 4)
    private BigDecimal quotedAmount;

    @Column(name = "expected_profit", precision = 19, scale = 4)
    private BigDecimal expectedProfit;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "status")
    private String status = "Draft";

    @Column(name = "owner")
    private String owner;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "pricing_mode")
    private String pricingMode = "RULES_BASED";

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_contact_name")
    private String customerContactName;

    @Column(name = "customer_phone")
    private String customerPhone;

    // ---------------------------------------------------------------------
    // Enterprise commercial terms / quote governance
    // ---------------------------------------------------------------------

    @Column(name = "currency", length = 3)
    private String currency = "USD";

    @Column(name = "incoterm", length = 20)
    private String incoterm;

    @Column(name = "tax_rate", precision = 9, scale = 4)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "tax_amount", precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "customs_cost", precision = 19, scale = 4)
    private BigDecimal customsCost = BigDecimal.ZERO;

    @Column(name = "insurance_cost", precision = 19, scale = 4)
    private BigDecimal insuranceCost = BigDecimal.ZERO;

    @Column(name = "customer_credit_terms")
    private String customerCreditTerms;

    @Column(name = "locked_amount", precision = 19, scale = 4)
    private BigDecimal lockedAmount;

    @Column(name = "locked_currency", length = 3)
    private String lockedCurrency;

    @Column(name = "price_locked_at")
    private Instant priceLockedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_version_id")
    private UUID acceptedVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CommercialQuote() {
    }

    public CommercialQuote(
            UUID tenant,
            String quoteReference,
            LocalDate date,
            String client,
            String route,
            String service,
            String commodity,
            BigDecimal weight,
            BigDecimal supplier,
            BigDecimal other,
            BigDecimal markup,
            BigDecimal quoted,
            BigDecimal profit,
            LocalDate valid,
            String status,
            String owner,
            LocalDate follow,
            String notes,
            String mode) {

        this.tenantId = tenant;
        this.quoteId = quoteReference;
        this.quoteDate = date;
        this.client = client;
        this.route = route;
        this.serviceType = service;
        this.commodity = commodity;
        this.chargeableWeightKg = weight;
        this.supplierCost = supplier;
        this.otherCost = other;
        this.markupPercent = markup;
        this.quotedAmount = quoted;
        this.expectedProfit = profit;
        this.validUntil = valid;
        this.status = status;
        this.owner = owner;
        this.followUpDate = follow;
        this.notes = notes;
        this.pricingMode = mode;
        this.currency = "USD";
        this.taxRate = BigDecimal.ZERO;
        this.taxAmount = BigDecimal.ZERO;
        this.customsCost = BigDecimal.ZERO;
        this.insuranceCost = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void setCustomerContact(String email, String contactName, String phone) {
        this.customerEmail = blankToNull(email);
        this.customerContactName = blankToNull(contactName);
        this.customerPhone = blankToNull(phone);
    }

    /**
     * Applies the commercial terms captured by the quote request/create DTO.
     * Null monetary values are normalized to zero so downstream accounting
     * calculations remain deterministic.
     */
    public void applyCommercialTerms(
            String currency,
            String incoterm,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal customsCost,
            BigDecimal insuranceCost,
            String customerCreditTerms) {

        this.currency = normalizeCurrency(currency);
        this.incoterm = blankToNull(incoterm);
        this.taxRate = nz(taxRate);
        this.taxAmount = nz(taxAmount);
        this.customsCost = nz(customsCost);
        this.insuranceCost = nz(insuranceCost);
        this.customerCreditTerms = blankToNull(customerCreditTerms);
        touch();
    }

    /** Approves the current quote for commercial release. */
    public void approve() {
        approve(null);
    }

    /** Approves the current quote and records the approving actor. */
    public void approve(UUID actor) {
        this.approvedBy = actor;
        this.approvedAt = Instant.now();
        touch();
    }

    /**
     * Freezes the currently quoted amount/currency. Once a quote has an
     * accepted version, changing the locked snapshot is deliberately blocked.
     */
    public void lockPrice() {
        if (quotedAmount == null) {
            throw new IllegalStateException("Cannot lock a quote without a quoted amount");
        }
        if (priceLockedAt != null && lockedAmount != null) {
            return;
        }
        this.lockedAmount = quotedAmount;
        this.lockedCurrency = normalizeCurrency(currency);
        this.priceLockedAt = Instant.now();
        touch();
    }

    /**
     * Associates the immutable commercial quote version accepted by the
     * customer and records the acceptance timestamp.
     */
    public void acceptVersion(UUID versionId) {
        if (versionId == null) {
            throw new IllegalArgumentException("Accepted quote version is required");
        }
        if (priceLockedAt == null || lockedAmount == null) {
            lockPrice();
        }
        this.acceptedVersionId = versionId;
        this.acceptedAt = Instant.now();
        this.status = "WON";
        touch();
    }

    public void changeStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Quote status is required");
        }

        String normalized = value.trim();
        if ("WON".equalsIgnoreCase(normalized)
                && validUntil != null
                && validUntil.isBefore(LocalDate.now())) {
            throw new IllegalStateException(
                    "Expired quotes cannot be marked WON; renew the quotation first");
        }

        if ("WON".equalsIgnoreCase(normalized)
                && (priceLockedAt == null || lockedAmount == null)) {
            throw new IllegalStateException(
                    "Quote price must be locked before the quote can be marked WON");
        }

        this.status = normalized;
        touch();
    }

    public String getEffectiveStatus(LocalDate asOf) {
        LocalDate effectiveDate = asOf == null ? LocalDate.now() : asOf;
        if (validUntil != null
                && validUntil.isBefore(effectiveDate)
                && !"WON".equalsIgnoreCase(status)
                && !"LOST".equalsIgnoreCase(status)
                && !"EXPIRED".equalsIgnoreCase(status)) {
            return "Expired";
        }
        return status;
    }

    public void updateFollowUp(LocalDate value) {
        this.followUpDate = value;
        touch();
    }

    public void updateNotes(String value) {
        this.notes = value;
        touch();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        currency = normalizeCurrency(currency);
        taxRate = nz(taxRate);
        taxAmount = nz(taxAmount);
        customsCost = nz(customsCost);
        insuranceCost = nz(insuranceCost);
    }

    @PreUpdate
    void preUpdate() {
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeCurrency(String value) {
        if (value == null || value.isBlank()) {
            return "USD";
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO code");
        }
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public LocalDate getQuoteDate() {
        return quoteDate;
    }

    public String getClient() {
        return client;
    }

    public String getRoute() {
        return route;
    }

    public String getServiceType() {
        return serviceType;
    }

    public String getCommodity() {
        return commodity;
    }

    public BigDecimal getChargeableWeightKg() {
        return chargeableWeightKg;
    }

    public BigDecimal getSupplierCost() {
        return supplierCost;
    }

    public BigDecimal getOtherCost() {
        return otherCost;
    }

    public BigDecimal getMarkupPercent() {
        return markupPercent;
    }

    public BigDecimal getQuotedAmount() {
        return quotedAmount;
    }

    public BigDecimal getExpectedProfit() {
        return expectedProfit;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public String getStatus() {
        return status;
    }

    public String getOwner() {
        return owner;
    }

    public LocalDate getFollowUpDate() {
        return followUpDate;
    }

    public String getNotes() {
        return notes;
    }

    public String getPricingMode() {
        return pricingMode;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getCustomerContactName() {
        return customerContactName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIncoterm() {
        return incoterm;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getCustomsCost() {
        return customsCost;
    }

    public BigDecimal getInsuranceCost() {
        return insuranceCost;
    }

    public String getCustomerCreditTerms() {
        return customerCreditTerms;
    }

    public BigDecimal getLockedAmount() {
        return lockedAmount;
    }

    public String getLockedCurrency() {
        return lockedCurrency;
    }

    public Instant getPriceLockedAt() {
        return priceLockedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public UUID getAcceptedVersionId() {
        return acceptedVersionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
