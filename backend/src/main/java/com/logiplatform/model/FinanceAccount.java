package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="finance_accounts", uniqueConstraints=@UniqueConstraint(name="uk_finance_account_code", columnNames={"tenant_id","account_code"}))
public class FinanceAccount {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
    @Column(name="account_code",nullable=false) private String accountCode;
    @Column(name="account_name",nullable=false) private String accountName;
    @Column(name="account_type",nullable=false) private String accountType;
    @Column(name="parent_code") private String parentCode;
    @Column(name="normal_balance",nullable=false) private String normalBalance;
    @Column(nullable=false) private boolean active=true;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt=Instant.now();
    protected FinanceAccount() {}
    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getAccountCode(){return accountCode;}
    public String getAccountName(){return accountName;} public String getAccountType(){return accountType;} public String getParentCode(){return parentCode;}
    public String getNormalBalance(){return normalBalance;} public boolean isActive(){return active;} public Instant getCreatedAt(){return createdAt;}
}
