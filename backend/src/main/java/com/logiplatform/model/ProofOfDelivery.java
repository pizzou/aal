package com.logiplatform.model;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="proof_of_delivery",uniqueConstraints=@UniqueConstraint(name="uk_pod_shipment",columnNames={"tenant_id","shipment_id"}))
public class ProofOfDelivery {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="shipment_id",nullable=false) private UUID shipmentId; @Column(name="trip_id") private UUID tripId;
 @Column(name="recipient_name") private String recipientName; @Column(name="recipient_phone") private String recipientPhone; @Column(name="signature_uri") private String signatureUri; @Column(name="photo_uri") private String photoUri; @Column(name="delivered_at",nullable=false) private Instant deliveredAt; @Column(name="latitude") private Double latitude; @Column(name="longitude") private Double longitude; @Column(name="failure_reason") private String failureReason; @Column(name="notes",columnDefinition="text") private String notes; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 protected ProofOfDelivery(){}
 public ProofOfDelivery(UUID t,UUID s,UUID trip,String name,String phone,String sig,String photo,Instant at,Double lat,Double lon,String failure,String notes){tenantId=t;shipmentId=s;tripId=trip;recipientName=name;recipientPhone=phone;signatureUri=sig;photoUri=photo;deliveredAt=at;latitude=lat;longitude=lon;failureReason=failure;this.notes=notes;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public UUID getTripId(){return tripId;} public String getRecipientName(){return recipientName;} public String getRecipientPhone(){return recipientPhone;} public String getSignatureUri(){return signatureUri;} public String getPhotoUri(){return photoUri;} public Instant getDeliveredAt(){return deliveredAt;} public Double getLatitude(){return latitude;} public Double getLongitude(){return longitude;} public String getFailureReason(){return failureReason;} public String getNotes(){return notes;} public Instant getCreatedAt(){return createdAt;}
}
