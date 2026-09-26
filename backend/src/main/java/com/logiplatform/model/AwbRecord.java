package com.logiplatform.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="awb_records",uniqueConstraints=@UniqueConstraint(name="uk_awb_no",columnNames={"tenant_id","awb_number"}))
public class AwbRecord {
 @Id @GeneratedValue private UUID id;
 @Column(name="tenant_id",nullable=false) private UUID tenantId;
 @Column(name="shipment_id",nullable=false) private UUID shipmentId;
 @Column(name="awb_number",nullable=false) private String awbNumber;
 @Column(name="awb_type",nullable=false) private String awbType;
 @Column(name="mawb_number") private String mawbNumber;
 @Column(name="hawb_number") private String hawbNumber;
 @Column(name="shipper_name") private String shipperName;
 @Column(name="shipper_address") private String shipperAddress;
 @Column(name="consignee_name") private String consigneeName;
 @Column(name="consignee_address") private String consigneeAddress;
 @Column(name="issuing_agent") private String issuingAgent;
 @Column(name="origin_airport") private String originAirport;
 @Column(name="destination_airport") private String destinationAirport;
 @Column(name="pieces") private Integer pieces;
 @Column(name="gross_weight_kg",precision=18,scale=3) private BigDecimal grossWeightKg;
 @Column(name="chargeable_weight_kg",precision=18,scale=3) private BigDecimal chargeableWeightKg;
 @Column(name="commodity",columnDefinition="text") private String commodity;
 @Column(name="hs_code") private String hsCode;
 @Column(name="special_handling",columnDefinition="text") private String specialHandling;
 @Column(name="dangerous_goods") private Boolean dangerousGoods=false;
 @Column(name="validation_status",nullable=false) private String validationStatus="PENDING";
 @Column(name="submission_status",nullable=false) private String submissionStatus="NOT_SUBMITTED";
 @Column(name="carrier_reference") private String carrierReference;
 @Column(name="document_uri") private String documentUri;
 @Column(name="cargo_acceptance_status",nullable=false) private String cargoAcceptanceStatus="PENDING";
 @Column(name="eawb_status",nullable=false) private String eawbStatus="NOT_READY";
 @Column(name="one_record_reference") private String oneRecordReference;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
 protected AwbRecord(){}
 public AwbRecord(UUID t,UUID s,String awb,String type,String mawb,String hawb,String shipper,String shipperAddr,String consignee,String consigneeAddr,String agent,String origin,String dest,Integer pieces,BigDecimal gross,BigDecimal charge,String commodity,String hs,String handling,Boolean dg){tenantId=t;shipmentId=s;awbNumber=awb;awbType=type;mawbNumber=mawb;hawbNumber=hawb;shipperName=shipper;shipperAddress=shipperAddr;consigneeName=consignee;consigneeAddress=consigneeAddr;issuingAgent=agent;originAirport=origin;destinationAirport=dest;this.pieces=pieces;grossWeightKg=gross;chargeableWeightKg=charge;this.commodity=commodity;hsCode=hs;specialHandling=handling;dangerousGoods=dg!=null&&dg;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public String getAwbNumber(){return awbNumber;} public String getAwbType(){return awbType;} public String getMawbNumber(){return mawbNumber;} public String getHawbNumber(){return hawbNumber;} public String getShipperName(){return shipperName;} public String getShipperAddress(){return shipperAddress;} public String getConsigneeName(){return consigneeName;} public String getConsigneeAddress(){return consigneeAddress;} public String getIssuingAgent(){return issuingAgent;} public String getOriginAirport(){return originAirport;} public String getDestinationAirport(){return destinationAirport;} public Integer getPieces(){return pieces;} public BigDecimal getGrossWeightKg(){return grossWeightKg;} public BigDecimal getChargeableWeightKg(){return chargeableWeightKg;} public String getCommodity(){return commodity;} public String getHsCode(){return hsCode;} public String getSpecialHandling(){return specialHandling;} public Boolean getDangerousGoods(){return dangerousGoods;} public String getValidationStatus(){return validationStatus;} public String getSubmissionStatus(){return submissionStatus;} public String getCarrierReference(){return carrierReference;} public String getDocumentUri(){return documentUri;} public String getCargoAcceptanceStatus(){return cargoAcceptanceStatus;} public String getEawbStatus(){return eawbStatus;} public String getOneRecordReference(){return oneRecordReference;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
 public void validateRecord(){
  if(awbNumber==null||awbNumber.isBlank()||shipmentId==null||originAirport==null||destinationAirport==null||pieces==null||pieces<=0||grossWeightKg==null||grossWeightKg.signum()<=0||chargeableWeightKg==null||chargeableWeightKg.signum()<=0)throw new IllegalArgumentException("AWB requires number, shipment, airports, positive pieces and weights");
  originAirport=originAirport.trim().toUpperCase();destinationAirport=destinationAirport.trim().toUpperCase();
  if(!originAirport.matches("[A-Z]{3}")||!destinationAirport.matches("[A-Z]{3}"))throw new IllegalArgumentException("Origin and destination must be valid 3-letter airport codes");
  if(originAirport.equals(destinationAirport))throw new IllegalArgumentException("AWB origin and destination must differ");
  if(chargeableWeightKg.compareTo(grossWeightKg)<0)throw new IllegalArgumentException("Chargeable weight cannot be lower than gross weight");
  if(awbType==null||!(awbType.equalsIgnoreCase("MAWB")||awbType.equalsIgnoreCase("HAWB")))throw new IllegalArgumentException("awbType must be MAWB or HAWB");
  if("HAWB".equalsIgnoreCase(awbType)&&(mawbNumber==null||mawbNumber.isBlank()))throw new IllegalArgumentException("HAWB requires its parent MAWB number");
  if(mawbNumber!=null&&!mawbNumber.isBlank())mawbNumber=normalizeAndValidateMawb(mawbNumber);
  if("MAWB".equalsIgnoreCase(awbType))awbNumber=normalizeAndValidateMawb(awbNumber); else hawbNumber=awbNumber.trim();
  validationStatus="VALID";updatedAt=Instant.now();
 }
 private static String normalizeAndValidateMawb(String value){String digits=value.replace("-","").replace(" ","");if(!digits.matches("\\d{11}"))throw new IllegalArgumentException("MAWB must contain 11 digits");int serial=Integer.parseInt(digits.substring(3,10));int check=Integer.parseInt(digits.substring(10));if(serial%7!=check)throw new IllegalArgumentException("MAWB check digit is invalid");return digits.substring(0,3)+"-"+digits.substring(3);}
 public void readyForSubmission(){if(!"VALID".equals(validationStatus))throw new IllegalStateException("AWB must be valid before submission");submissionStatus="READY_FOR_SUBMISSION";eawbStatus="READY";updatedAt=Instant.now();}
 public void submitted(String ref){if(!"VALID".equals(validationStatus))throw new IllegalStateException("AWB must be valid before submission");submissionStatus="SUBMITTED";carrierReference=ref;eawbStatus="SUBMITTED";updatedAt=Instant.now();}
 public void acceptance(String status,String reference,String oneRecord){cargoAcceptanceStatus=status;eawbStatus="ACCEPTED".equalsIgnoreCase(status)?"ACCEPTED":"REJECTED";if(reference!=null&&!reference.isBlank())carrierReference=reference;if(oneRecord!=null&&!oneRecord.isBlank())oneRecordReference=oneRecord;updatedAt=Instant.now();}
}
