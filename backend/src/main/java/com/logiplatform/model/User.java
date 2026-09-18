package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="tenant_id", nullable=false, updatable=false) private UUID tenantId;
    @Column(nullable=false, unique=true, length=255) private String email;
    @Column(name="password_hash", nullable=false, length=255) private String passwordHash;
    @Column(nullable=false, length=50) private String role;
    @Column(name="display_name", length=255) private String displayName;
    @Column(length=50) private String phone;
    @Column(nullable=false) private boolean active=true;
    @Column(name="failed_login_attempts", nullable=false) private int failedLoginAttempts;
    @Column(name="locked_until") private Instant lockedUntil;
    @Column(name="token_version", nullable=false) private long tokenVersion;
    @Column(name="customer_client_id") private String customerClientId;
    @Column(name="must_change_password", nullable=false) private boolean mustChangePassword;
    @Column(name="login_otp_hash") private String loginOtpHash;
    @Column(name="login_otp_expires_at") private Instant loginOtpExpiresAt;
    @Column(name="login_otp_attempts", nullable=false) private int loginOtpAttempts;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt=Instant.now();
    protected User() {}
    public User(UUID tenantId,String email,String passwordHash,String role){this.tenantId=tenantId;this.email=email;this.passwordHash=passwordHash;this.role=role;this.createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getEmail(){return email;} public String getPasswordHash(){return passwordHash;} public String getRole(){return role;}
    public String getDisplayName(){return displayName;} public String getPhone(){return phone;} public boolean isActive(){return active;} public int getFailedLoginAttempts(){return failedLoginAttempts;} public Instant getLockedUntil(){return lockedUntil;} public long getTokenVersion(){return tokenVersion;} public String getCustomerClientId(){return customerClientId;} public boolean isMustChangePassword(){return mustChangePassword;} public String getLoginOtpHash(){return loginOtpHash;} public Instant getLoginOtpExpiresAt(){return loginOtpExpiresAt;} public int getLoginOtpAttempts(){return loginOtpAttempts;} public Instant getCreatedAt(){return createdAt;}
    public void setEmail(String v){email=v;} public void setPasswordHash(String v){passwordHash=v;} public void setRole(String v){role=v;} public void setDisplayName(String v){displayName=v;} public void setPhone(String v){phone=v;} public void setActive(boolean v){active=v;} public void setFailedLoginAttempts(int v){failedLoginAttempts=v;} public void setLockedUntil(Instant v){lockedUntil=v;} public void setTokenVersion(long v){tokenVersion=v;} public void incrementTokenVersion(){tokenVersion++;} public void setCustomerClientId(String v){customerClientId=v;} public void setMustChangePassword(boolean v){mustChangePassword=v;} public void setLoginOtpHash(String v){loginOtpHash=v;} public void setLoginOtpExpiresAt(Instant v){loginOtpExpiresAt=v;} public void setLoginOtpAttempts(int v){loginOtpAttempts=v;}
}
