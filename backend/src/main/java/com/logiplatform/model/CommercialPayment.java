package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="commercial_payments",
       uniqueConstraints=@UniqueConstraint(name="uk_commercial_payment_idempotency",
           columnNames={"tenant_id","idempotency_key"}))
public class CommercialPayment {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
    @Column(name="invoice_id",nullable=false,updatable=false) private UUID invoiceId;
    @Column(name="amount",nullable=false,precision=19,scale=4) private BigDecimal amount;
    @Column(name="currency",nullable=false,length=10) private String currency;
    @Column(name="idempotency_key",nullable=false,length=255) private String idempotencyKey;
    @Column(name="reference",length=255) private String reference;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt=Instant.now();

    protected CommercialPayment(){}
    public CommercialPayment(UUID tenantId,UUID invoiceId,BigDecimal amount,String currency,String key,String reference){
        this.tenantId=tenantId;this.invoiceId=invoiceId;this.amount=amount;this.currency=currency;
        this.idempotencyKey=key;this.reference=reference;
    }
    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getInvoiceId(){return invoiceId;}
    public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;}
    public String getIdempotencyKey(){return idempotencyKey;} public String getReference(){return reference;} public Instant getCreatedAt(){return createdAt;}
}
