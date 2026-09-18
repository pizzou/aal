package com.logiplatform.model;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="operational_exceptions")
public class OperationalException {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="shipment_id") private UUID shipmentId; @Column(name="trip_id") private UUID tripId; @Column(name="vehicle_id") private UUID vehicleId; @Column(name="type",nullable=false) private String type; @Column(name="severity",nullable=false) private String severity; @Column(name="status",nullable=false) private String status="OPEN"; @Column(name="owner") private String owner; @Column(name="description",nullable=false,columnDefinition="text") private String description; @Column(name="action_taken",columnDefinition="text") private String actionTaken; @Column(name="resolution",columnDefinition="text") private String resolution; @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now(); @Column(name="resolved_at") private Instant resolvedAt;
 protected OperationalException(){}
 public OperationalException(UUID t,UUID s,UUID trip,UUID vehicle,String type,String severity,String owner,String desc){tenantId=t;shipmentId=s;tripId=trip;vehicleId=vehicle;this.type=type;this.severity=severity;this.owner=owner;description=desc;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public UUID getTripId(){return tripId;} public UUID getVehicleId(){return vehicleId;} public String getType(){return type;} public String getSeverity(){return severity;} public String getStatus(){return status;} public String getOwner(){return owner;} public String getDescription(){return description;} public String getActionTaken(){return actionTaken;} public String getResolution(){return resolution;} public Instant getCreatedAt(){return createdAt;} public Instant getResolvedAt(){return resolvedAt;}
 public void assign(String o){owner=o;} public void action(String a){actionTaken=a;} public void resolve(String r){resolution=r;status="RESOLVED";resolvedAt=Instant.now();}
}
