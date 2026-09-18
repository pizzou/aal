package com.logiplatform.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(
        name = "commercial_invoices",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_invoice_ref",
                columnNames = {
                        "tenant_id",
                        "invoice_no"
                }
        )
)
public class CommercialInvoice {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(
            name = "tenant_id",
            nullable = false
    )
    private UUID tenantId;

    @Column(
            name = "invoice_no",
            nullable = false
    )
    private String invoiceNo;

    @Column(
            name = "issue_date",
            nullable = false
    )
    private LocalDate issueDate;

    @Column(name = "client")
    private String client;

    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Column(
            name = "currency",
            nullable = false
    )
    private String currency;

    @Column(
            name = "invoice_amount",
            nullable = false,
            precision = 19,
            scale = 4
    )
    private BigDecimal invoiceAmount;

    @Column(
            name = "amount_paid",
            nullable = false,
            precision = 19,
            scale = 4
    )
    private BigDecimal amountPaid =
            BigDecimal.ZERO;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "last_follow_up")
    private LocalDate lastFollowUp;

    @Column(name = "next_follow_up")
    private LocalDate nextFollowUp;

    @Column(name = "owner")
    private String owner;

    @Column(
            name = "notes",
            columnDefinition = "text"
    )
    private String notes;

    @Version
    @Column(
            name = "version",
            nullable = false
    )
    private long version;

    protected CommercialInvoice() {
    }

    public CommercialInvoice(
            UUID tenantId,
            String invoiceNo,
            LocalDate issueDate,
            String client,
            UUID shipmentId,
            String currency,
            BigDecimal invoiceAmount,
            LocalDate dueDate,
            String owner
    ) {

        this.tenantId =
                tenantId;

        this.invoiceNo =
                invoiceNo;

        this.issueDate =
                issueDate;

        this.client =
                client;

        this.shipmentId =
                shipmentId;

        this.currency =
                currency;

        this.invoiceAmount =
                invoiceAmount;

        this.dueDate =
                dueDate;

        this.owner =
                owner;
    }

    public UUID getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public String getClient() {
        return client;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getInvoiceAmount() {
        return invoiceAmount;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public BigDecimal getBalance() {
        return invoiceAmount.subtract(
                amountPaid
        );
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getLastFollowUp() {
        return lastFollowUp;
    }

    public LocalDate getNextFollowUp() {
        return nextFollowUp;
    }

    public String getOwner() {
        return owner;
    }

    public String getNotes() {
        return notes;
    }

    public long getDaysOverdue() {

        if (dueDate == null
                || getBalance().signum() <= 0) {

            return 0;
        }

        return Math.max(
                0,
                ChronoUnit.DAYS.between(
                        dueDate,
                        LocalDate.now()
                )
        );
    }

    public String getAgingBucket() {

        long days =
                getDaysOverdue();

        if (getBalance().signum() <= 0) {
            return "Paid";
        }

        if (days == 0) {
            return "Current";
        }

        if (days <= 30) {
            return "1-30 Days";
        }

        if (days <= 60) {
            return "31-60 Days";
        }

        if (days <= 90) {
            return "61-90 Days";
        }

        return "90+ Days";
    }

    public String getStatus() {

        if (getBalance().signum() <= 0) {
            return "Paid";
        }

        if (getDaysOverdue() > 0) {
            return getAmountPaid().signum() > 0
                    ? "Partially Paid"
                    : "Overdue";
        }

        return getAmountPaid().signum() > 0
                ? "Partially Paid"
                : "Unpaid";
    }

    public void applyPayment(
            BigDecimal amount
    ) {

        if (amount == null
                || amount.signum() <= 0) {

            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero"
            );
        }

        if (getBalance().compareTo(amount) < 0) {

            throw new IllegalArgumentException(
                    "Payment exceeds invoice balance"
            );
        }

        amountPaid =
                amountPaid.add(amount);
    }

    /**
     * Used only during controlled workbook migration.
     *
     * The imported payment is validated against the invoice total so bad
     * spreadsheet values cannot create an impossible receivable.
     */
    public void setImportedCollectionData(
            BigDecimal paid,
            LocalDate lastFollowUp,
            LocalDate nextFollowUp,
            String notes
    ) {

        BigDecimal value =
                paid == null
                        ? BigDecimal.ZERO
                        : paid;

        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "Imported amount paid cannot be negative"
            );
        }

        if (value.compareTo(
                invoiceAmount
        ) > 0) {

            throw new IllegalArgumentException(
                    "Imported amount paid cannot exceed invoice amount"
            );
        }

        this.amountPaid =
                value;

        this.lastFollowUp =
                lastFollowUp;

        this.nextFollowUp =
                nextFollowUp;

        this.notes =
                notes;
    }
}