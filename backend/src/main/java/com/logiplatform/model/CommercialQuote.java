package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "commercial_quotes", uniqueConstraints = @UniqueConstraint(name = "uk_quote_ref", columnNames = {
        "tenant_id", "quote_id" }))
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
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CommercialQuote() {
    }

    public CommercialQuote(UUID t, String q, LocalDate d, String client, String route, String service, String commodity,
            BigDecimal weight, BigDecimal supplier, BigDecimal other, BigDecimal markup, BigDecimal quoted,
            BigDecimal profit, LocalDate valid, String status, String owner, LocalDate follow, String notes,
            String mode) {
        tenantId = t;
        quoteId = q;
        quoteDate = d;
        this.client = client;
        this.route = route;
        serviceType = service;
        this.commodity = commodity;
        chargeableWeightKg = weight;
        supplierCost = supplier;
        otherCost = other;
        markupPercent = markup;
        quotedAmount = quoted;
        expectedProfit = profit;
        validUntil = valid;
        this.status = status;
        this.owner = owner;
        followUpDate = follow;
        this.notes = notes;
        pricingMode = mode;
    }

    public void setCustomerContact(String email, String contactName, String phone) {
        customerEmail = email == null || email.isBlank() ? null : email.trim();
        customerContactName = contactName == null || contactName.isBlank() ? null : contactName.trim();
        customerPhone = phone == null || phone.isBlank() ? null : phone.trim();
    }

    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerContactName() { return customerContactName; }
    public String getCustomerPhone() { return customerPhone; }

    public void changeStatus(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Quote status is required");
        String normalized = value.trim();
        if ("WON".equalsIgnoreCase(normalized) && validUntil != null && validUntil.isBefore(LocalDate.now()))
            throw new IllegalStateException("Expired quotes cannot be marked WON; renew the quotation first");
        status = normalized;
    }

    public String getEffectiveStatus(LocalDate asOf) {
        if (validUntil != null && validUntil.isBefore(asOf) && !"WON".equalsIgnoreCase(status)
                && !"LOST".equalsIgnoreCase(status) && !"EXPIRED".equalsIgnoreCase(status))
            return "Expired";
        return status;
    }

    public void updateFollowUp(LocalDate value) {
        followUpDate = value;
    }

    public void updateNotes(String value) {
        notes = value;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
