package com.logiplatform.model;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="transport_legs")
public class TransportLeg {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="shipment_id",nullable=false) private UUID shipmentId;
 @Column(name="sequence_no",nullable=false) private Integer sequenceNo; @Column(name="mode",nullable=false) private String mode; @Column(name="origin",nullable=false) private String origin; @Column(name="destination",nullable=false) private String destination;
 @Column(name="carrier_name") private String carrierName; @Column(name="carrier_reference") private String carrierReference; @Column(name="planned_departure") private Instant plannedDeparture; @Column(name="planned_arrival") private Instant plannedArrival; @Column(name="actual_departure") private Instant actualDeparture; @Column(name="actual_arrival") private Instant actualArrival; @Column(name="status",nullable=false) private String status="PLANNED";
 protected TransportLeg(){} public TransportLeg(UUID t,UUID s,Integer n,String m,String o,String d,String c,Instant dep,Instant arr){tenantId=t;shipmentId=s;sequenceNo=n;mode=m;origin=o;destination=d;carrierName=c;plannedDeparture=dep;plannedArrival=arr;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public Integer getSequenceNo(){return sequenceNo;} public String getMode(){return mode;} public String getOrigin(){return origin;} public String getDestination(){return destination;} public String getCarrierName(){return carrierName;} public String getCarrierReference(){return carrierReference;} public Instant getPlannedDeparture(){return plannedDeparture;} public Instant getPlannedArrival(){return plannedArrival;} public Instant getActualDeparture(){return actualDeparture;} public Instant getActualArrival(){return actualArrival;} public String getStatus(){return status;}
 public void setCarrierReference(String v){carrierReference=v;} public void setStatus(String v){status=v;} public void setActualDeparture(Instant v){actualDeparture=v;} public void setActualArrival(Instant v){actualArrival=v;}
}
