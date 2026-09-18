package com.logiplatform.model;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="cargo_documents")
public class CargoDocument {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="shipment_id",nullable=false) private UUID shipmentId; @Column(name="document_type",nullable=false) private String documentType; @Column(name="template_code",nullable=false) private String templateCode; @Column(name="file_uri") private String fileUri; @Column(name="content_hash") private String contentHash; @Column(name="status",nullable=false) private String status="DRAFT"; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 protected CargoDocument(){} public CargoDocument(UUID t,UUID s,String type,String template,String uri,String hash){tenantId=t;shipmentId=s;documentType=type;templateCode=template;fileUri=uri;contentHash=hash;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public String getDocumentType(){return documentType;} public String getTemplateCode(){return templateCode;} public String getFileUri(){return fileUri;} public String getContentHash(){return contentHash;} public String getStatus(){return status;} public Instant getCreatedAt(){return createdAt;}
}
