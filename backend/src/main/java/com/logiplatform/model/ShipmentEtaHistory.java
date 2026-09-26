package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="shipment_eta_history")
public class ShipmentEtaHistory {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
    @Column(name="shipment_id",nullable=false,updatable=false) private UUID shipmentId;
    @Column(nullable=false,updatable=false) private String source;
    @Column(name="provider_event_id") private String providerEventId;
    @Column(name="flight_number") private String flightNumber;
    @Column(name="flight_status") private String flightStatus;
    @Column(name="previous_etd") private Instant previousEtd;
    @Column(name="new_etd") private Instant newEtd;
    @Column(name="previous_eta") private Instant previousEta;
    @Column(name="new_eta") private Instant newEta;
    @Column(name="reason") private String reason;
    @Column(name="raw_payload_hash") private String rawPayloadHash;
    @Column(name="observed_at",nullable=false) private Instant observedAt=Instant.now();
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt=Instant.now();
    protected ShipmentEtaHistory(){}
    public ShipmentEtaHistory(UUID tenantId,UUID shipmentId,String source,String providerEventId,String flightNumber,String flightStatus,Instant previousEtd,Instant newEtd,Instant previousEta,Instant newEta,String reason,String rawPayloadHash){this.tenantId=tenantId;this.shipmentId=shipmentId;this.source=source;this.providerEventId=providerEventId;this.flightNumber=flightNumber;this.flightStatus=flightStatus;this.previousEtd=previousEtd;this.newEtd=newEtd;this.previousEta=previousEta;this.newEta=newEta;this.reason=reason;this.rawPayloadHash=rawPayloadHash;}
    public UUID getId(){return id;} public UUID getShipmentId(){return shipmentId;} public String getSource(){return source;} public String getProviderEventId(){return providerEventId;} public String getFlightNumber(){return flightNumber;} public String getFlightStatus(){return flightStatus;} public Instant getPreviousEtd(){return previousEtd;} public Instant getNewEtd(){return newEtd;} public Instant getPreviousEta(){return previousEta;} public Instant getNewEta(){return newEta;} public String getReason(){return reason;} public Instant getObservedAt(){return observedAt;} public Instant getCreatedAt(){return createdAt;}
}
