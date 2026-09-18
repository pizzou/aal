package com.logiplatform.model;

import jakarta.persistence.*; import java.math.BigDecimal; import java.util.UUID;
@Entity @Table(name="cargo_pieces")
public class CargoPiece {
 @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="shipment_id",nullable=false) private UUID shipmentId; @Column(name="piece_no",nullable=false) private Integer pieceNo;
 @Column(name="length_cm",nullable=false,precision=12,scale=3) private BigDecimal lengthCm; @Column(name="width_cm",nullable=false,precision=12,scale=3) private BigDecimal widthCm; @Column(name="height_cm",nullable=false,precision=12,scale=3) private BigDecimal heightCm; @Column(name="weight_kg",nullable=false,precision=12,scale=3) private BigDecimal weightKg; @Column(name="stackable",nullable=false) private boolean stackable=true; @Column(name="temperature_controlled",nullable=false) private boolean temperatureControlled=false;
 protected CargoPiece(){} public CargoPiece(UUID t,UUID s,Integer n,BigDecimal l,BigDecimal w,BigDecimal h,BigDecimal kg,boolean stack,boolean temp){tenantId=t;shipmentId=s;pieceNo=n;lengthCm=l;widthCm=w;heightCm=h;weightKg=kg;stackable=stack;temperatureControlled=temp;}
 public UUID getId(){return id;} public UUID getShipmentId(){return shipmentId;} public Integer getPieceNo(){return pieceNo;} public BigDecimal getLengthCm(){return lengthCm;} public BigDecimal getWidthCm(){return widthCm;} public BigDecimal getHeightCm(){return heightCm;} public BigDecimal getWeightKg(){return weightKg;} public boolean isStackable(){return stackable;} public boolean isTemperatureControlled(){return temperatureControlled;} public BigDecimal volumeM3(){return lengthCm.multiply(widthCm).multiply(heightCm).divide(BigDecimal.valueOf(1_000_000),6,java.math.RoundingMode.HALF_UP);}
}
