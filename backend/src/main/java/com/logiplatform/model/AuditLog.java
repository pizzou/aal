package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_tenant_created", columnList = "tenant_id,created_at"),
        @Index(name = "idx_audit_tenant_user", columnList = "tenant_id,user_id")
})
public class AuditLog {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id", nullable=false, updatable=false) private UUID tenantId;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="user_id")
    private User user;
    @Column(nullable=false) private String action;
    @Column(name="resource_type", nullable=false) private String resourceType;
    @Column(name="resource_id") private UUID resourceId;
    @Column(nullable=false) private String method;
    @Column(nullable=false, columnDefinition="text") private String path;
    @Column(name="ip_address") private String ipAddress;
    @Column(name="user_agent", columnDefinition="text") private String userAgent;
    @Column(name="status_code") private Integer statusCode;
    @Column(nullable=false) private boolean success;
    @Column(name="before_state", columnDefinition="text") private String beforeState;
    @Column(name="after_state", columnDefinition="text") private String afterState;
    @Column(name="correlation_id", length=120) private String correlationId;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt=Instant.now();

    protected AuditLog() {}

    public AuditLog(UUID tenantId, User user, String action, String resourceType, UUID resourceId,
                    String method, String path, String ipAddress, String userAgent,
                    Integer statusCode, boolean success) {
        this.tenantId=tenantId; this.user=user; this.action=action; this.resourceType=resourceType;
        this.resourceId=resourceId; this.method=method; this.path=path; this.ipAddress=ipAddress;
        this.userAgent=userAgent; this.statusCode=statusCode; this.success=success;
    }
    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public User getUser(){return user;}
    public String getAction(){return action;} public String getResourceType(){return resourceType;} public UUID getResourceId(){return resourceId;}
    public String getMethod(){return method;} public String getPath(){return path;} public String getIpAddress(){return ipAddress;}
    public String getUserAgent(){return userAgent;} public Integer getStatusCode(){return statusCode;} public boolean isSuccess(){return success;}
    public String getBeforeState(){return beforeState;} public String getAfterState(){return afterState;} public String getCorrelationId(){return correlationId;}
    public Instant getCreatedAt(){return createdAt;}
    public void setState(String before,String after,String correlation){this.beforeState=before;this.afterState=after;this.correlationId=correlation;}
}
