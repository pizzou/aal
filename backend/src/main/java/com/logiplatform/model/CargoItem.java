package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="cargo_items")
public class CargoItem {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false,updatable=false) private UUID tenantId;
 @Column(name="shipment_id",nullable=false,updatable=false) private UUID shipmentId; @Column(name="line_no",nullable=false) private Integer lineNo;
 @Column(nullable=false) private String description; @Column(name="package_type") private String packageType; @Column(nullable=false) private Integer quantity=1;
 @Column(name="gross_weight_kg",precision=19,scale=3) private BigDecimal grossWeightKg; @Column(name="volume_m3",precision=19,scale=6) private BigDecimal volumeM3;
 @Column(name="length_cm",precision=19,scale=3) private BigDecimal lengthCm; @Column(name="width_cm",precision=19,scale=3) private BigDecimal widthCm; @Column(name="height_cm",precision=19,scale=3) private BigDecimal heightCm;
 @Column(name="hs_code") private String hsCode; @Column(name="country_of_origin") private String countryOfOrigin; @Column(name="declared_value",precision=19,scale=4) private BigDecimal declaredValue; private String currency;
 @Column(name="dangerous_goods",nullable=false) private boolean dangerousGoods; @Column(name="dg_class") private String dgClass; @Column(name="un_number") private String unNumber;
 @Column(name="temperature_controlled",nullable=false) private boolean temperatureControlled; @Column(name="min_temperature_c",precision=8,scale=2) private BigDecimal minTemperatureC; @Column(name="max_temperature_c",precision=8,scale=2) private BigDecimal maxTemperatureC;
 @Column(nullable=false) private boolean stackable=true; @Column(nullable=false) private boolean fragile=false; @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt=Instant.now(); @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
 protected CargoItem(){}
 public CargoItem(UUID t,UUID s,Integer n,String d,String p,Integer q,BigDecimal kg,BigDecimal vol,BigDecimal l,BigDecimal w,BigDecimal h,String hs,String origin,BigDecimal value,String cur,boolean dg,String dgClass,String un,boolean temp,BigDecimal min,BigDecimal max,boolean stack,boolean frag){tenantId=t;shipmentId=s;lineNo=n;description=d;packageType=p;quantity=q==null?1:q;grossWeightKg=kg;volumeM3=vol;lengthCm=l;widthCm=w;heightCm=h;hsCode=hs;countryOfOrigin=origin;declaredValue=value;currency=cur;dangerousGoods=dg;this.dgClass=dgClass;unNumber=un;temperatureControlled=temp;minTemperatureC=min;maxTemperatureC=max;stackable=stack;fragile=frag;}
 public UUID getId(){return id;} public UUID getShipmentId(){return shipmentId;} public Integer getLineNo(){return lineNo;} public String getDescription(){return description;} public String getPackageType(){return packageType;} public Integer getQuantity(){return quantity;} public BigDecimal getGrossWeightKg(){return grossWeightKg;} public BigDecimal getVolumeM3(){return volumeM3;} public String getHsCode(){return hsCode;} public String getCountryOfOrigin(){return countryOfOrigin;} public BigDecimal getDeclaredValue(){return declaredValue;} public String getCurrency(){return currency;} public boolean isDangerousGoods(){return dangerousGoods;} public String getDgClass(){return dgClass;} public String getUnNumber(){return unNumber;} public boolean isTemperatureControlled(){return temperatureControlled;} public BigDecimal getMinTemperatureC(){return minTemperatureC;} public BigDecimal getMaxTemperatureC(){return maxTemperatureC;} public boolean isStackable(){return stackable;} public boolean isFragile(){return fragile;} public Instant getCreatedAt(){return createdAt;}
}
