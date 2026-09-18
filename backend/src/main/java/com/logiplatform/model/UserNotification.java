package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="user_notifications")
public class UserNotification {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
    @Column(name="user_id",nullable=false,updatable=false) private UUID userId;
    @Column(nullable=false,length=60) private String type;
    @Column(nullable=false,length=255) private String title;
    @Column(nullable=false,columnDefinition="TEXT") private String message;
    @Column(length=1000) private String link;
    @Column(nullable=false) private boolean read;
    @Column(name="read_at") private Instant readAt;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt=Instant.now();
    protected UserNotification(){}
    public UserNotification(UUID tenantId,UUID userId,String type,String title,String message,String link){this.tenantId=tenantId;this.userId=userId;this.type=type;this.title=title;this.message=message;this.link=link;this.createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getUserId(){return userId;} public String getType(){return type;} public String getTitle(){return title;} public String getMessage(){return message;} public String getLink(){return link;} public boolean isRead(){return read;} public Instant getReadAt(){return readAt;} public Instant getCreatedAt(){return createdAt;}
    public void setRead(boolean v){read=v;} public void setReadAt(Instant v){readAt=v;}
}
