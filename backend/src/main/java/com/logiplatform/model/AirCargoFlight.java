package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="air_cargo_flights",
       uniqueConstraints=@UniqueConstraint(name="uk_air_flight",
           columnNames={"tenant_id","carrier_code","flight_number","departure_time"}))
public class AirCargoFlight {
    @Id @GeneratedValue private UUID id;
    @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
    @Column(name="carrier_code",nullable=false,length=40) private String carrierCode;
    @Column(name="carrier_name") private String carrierName;
    @Column(name="flight_number",nullable=false,length=80) private String flightNumber;
    @Column(name="origin_code",nullable=false,length=10) private String originCode;
    @Column(name="destination_code",nullable=false,length=10) private String destinationCode;
    @Column(name="departure_time",nullable=false) private Instant departureTime;
    @Column(name="arrival_time") private Instant arrivalTime;
    @Column(name="total_capacity_kg",nullable=false,precision=18,scale=3) private BigDecimal totalCapacityKg;
    @Column(name="available_capacity_kg",nullable=false,precision=18,scale=3) private BigDecimal availableCapacityKg;
    @Column(name="status",nullable=false,length=50) private String status="SCHEDULED";
    @Column(name="source",nullable=false,length=80) private String source="EXTERNAL";
    @Version @Column(name="version",nullable=false) private long version;
    @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();

    protected AirCargoFlight(){}

    public AirCargoFlight(UUID t,String cc,String cn,String fn,String o,String d,Instant dep,Instant arr,
                          BigDecimal total,BigDecimal avail,String source){
        if(total==null||total.signum()<0||avail==null||avail.signum()<0||avail.compareTo(total)>0)
            throw new IllegalArgumentException("Invalid flight capacity");
        tenantId=t; carrierCode=cc.trim().toUpperCase(); carrierName=cn;
        flightNumber=fn.trim().toUpperCase(); originCode=o.trim().toUpperCase();
        destinationCode=d.trim().toUpperCase(); departureTime=dep; arrivalTime=arr;
        totalCapacityKg=total; availableCapacityKg=avail;
        this.source=source==null||source.isBlank()?"EXTERNAL":source;
    }

    public UUID getId(){return id;} public UUID getTenantId(){return tenantId;}
    public String getCarrierCode(){return carrierCode;} public String getCarrierName(){return carrierName;}
    public String getFlightNumber(){return flightNumber;} public String getOriginCode(){return originCode;}
    public String getDestinationCode(){return destinationCode;} public Instant getDepartureTime(){return departureTime;}
    public Instant getArrivalTime(){return arrivalTime;} public BigDecimal getTotalCapacityKg(){return totalCapacityKg;}
    public BigDecimal getAvailableCapacityKg(){return availableCapacityKg;} public String getStatus(){return status;}
    public String getSource(){return source;} public Instant getUpdatedAt(){return updatedAt;} public long getVersion(){return version;}

    public void refreshCapacity(BigDecimal total,BigDecimal available,Instant arrival,String source){
        if(total==null||available==null||total.signum()<0||available.signum()<0||available.compareTo(total)>0)
            throw new IllegalArgumentException("Invalid flight capacity");
        totalCapacityKg=total;availableCapacityKg=available;arrivalTime=arrival;
        this.source=source;updatedAt=Instant.now();
    }

    public boolean reserve(BigDecimal kg){
        if(kg==null||kg.signum()<=0||availableCapacityKg.compareTo(kg)<0)return false;
        availableCapacityKg=availableCapacityKg.subtract(kg); updatedAt=Instant.now(); return true;
    }

    public void release(BigDecimal kg){
        if(kg==null||kg.signum()<=0)return;
        availableCapacityKg=availableCapacityKg.add(kg);
        if(availableCapacityKg.compareTo(totalCapacityKg)>0) availableCapacityKg=totalCapacityKg;
        updatedAt=Instant.now();
    }
}
