package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="air_cargo_bookings", uniqueConstraints=@UniqueConstraint(name="uk_air_booking_provider_ref", columnNames={"tenant_id","provider","provider_reference"}))
public class AirCargoBooking {
 @Id @GeneratedValue private UUID id;
 @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
 @Column(name="shipment_id",nullable=false) private UUID shipmentId;
 @Column(name="carrier_code",nullable=false) private String carrierCode;
 @Column(name="carrier_name") private String carrierName;
 @Column(name="flight_number",nullable=false) private String flightNumber;
 @Column(name="departure_time",nullable=false) private Instant departureTime;
 @Column(name="arrival_time") private Instant arrivalTime;
 @Column(name="origin_code",nullable=false) private String originCode;
 @Column(name="destination_code",nullable=false) private String destinationCode;
 @Column(name="requested_weight_kg",nullable=false,precision=18,scale=3) private BigDecimal requestedWeightKg;
 @Column(name="confirmed_weight_kg",precision=18,scale=3) private BigDecimal confirmedWeightKg;
 @Column(name="status",nullable=false) private String status;
 @Column(name="provider") private String provider;
 @Column(name="provider_reference") private String providerReference;
 @Column(name="confirmation_number") private String confirmationNumber;
 @Column(name="idempotency_key",nullable=false) private String idempotencyKey;
 @Column(name="raw_response",columnDefinition="text") private String rawResponse;
 @Version @Column(name="version",nullable=false) private long version;
 @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
 protected AirCargoBooking(){}
 public AirCargoBooking(UUID tenantId,UUID shipmentId,String carrierCode,String carrierName,String flightNumber,Instant departureTime,Instant arrivalTime,String originCode,String destinationCode,BigDecimal requestedWeightKg,String status,String provider,String idempotencyKey){
  this.tenantId=tenantId;this.shipmentId=shipmentId;this.carrierCode=carrierCode;this.carrierName=carrierName;this.flightNumber=flightNumber;this.departureTime=departureTime;this.arrivalTime=arrivalTime;this.originCode=originCode;this.destinationCode=destinationCode;this.requestedWeightKg=requestedWeightKg;this.status=status;this.provider=provider;this.idempotencyKey=idempotencyKey;
 }
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public String getCarrierCode(){return carrierCode;} public String getCarrierName(){return carrierName;} public String getFlightNumber(){return flightNumber;} public Instant getDepartureTime(){return departureTime;} public Instant getArrivalTime(){return arrivalTime;} public String getOriginCode(){return originCode;} public String getDestinationCode(){return destinationCode;} public BigDecimal getRequestedWeightKg(){return requestedWeightKg;} public BigDecimal getConfirmedWeightKg(){return confirmedWeightKg;} public String getStatus(){return status;} public String getProvider(){return provider;} public String getProviderReference(){return providerReference;} public String getConfirmationNumber(){return confirmationNumber;} public String getIdempotencyKey(){return idempotencyKey;} public String getRawResponse(){return rawResponse;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
 public void pending(String providerReference,String raw){this.providerReference=providerReference;this.rawResponse=raw;this.status="PENDING";this.updatedAt=Instant.now();}
 public void confirm(BigDecimal weight,String providerReference,String confirmation,String raw){this.confirmedWeightKg=weight;this.providerReference=providerReference;this.confirmationNumber=confirmation;this.rawResponse=raw;this.status="CONFIRMED";this.updatedAt=Instant.now();}
 public void fail(String raw){this.status="FAILED";this.rawResponse=raw;this.updatedAt=Instant.now();}
}
