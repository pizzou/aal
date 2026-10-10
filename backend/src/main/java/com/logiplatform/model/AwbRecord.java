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

 // Structured legal-party, routing, rating and valuation data. Legacy text columns remain for compatibility.
 @Column(name="airline_prefix", length=3) private String airlinePrefix;
 @Column(name="airline_serial", length=8) private String airlineSerial;
 @Column(name="shipper_street_address", columnDefinition="text") private String shipperStreetAddress;
 @Column(name="shipper_postal_code", length=40) private String shipperPostalCode;
 @Column(name="shipper_contact_name", length=255) private String shipperContactName;
 @Column(name="shipper_phone", length=80) private String shipperPhone;
 @Column(name="shipper_email", length=255) private String shipperEmail;
 @Column(name="shipper_tax_id", length=120) private String shipperTaxId;
 @Column(name="shipper_eori_number", length=120) private String shipperEoriNumber;
 @Column(name="consignee_street_address", columnDefinition="text") private String consigneeStreetAddress;
 @Column(name="consignee_postal_code", length=40) private String consigneePostalCode;
 @Column(name="consignee_contact_name", length=255) private String consigneeContactName;
 @Column(name="consignee_phone", length=80) private String consigneePhone;
 @Column(name="consignee_email", length=255) private String consigneeEmail;
 @Column(name="consignee_tax_id", length=120) private String consigneeTaxId;
 @Column(name="consignee_eori_number", length=120) private String consigneeEoriNumber;
 @Column(name="issuing_agent_city", length=255) private String issuingAgentCity;
 @Column(name="iata_cargo_agent_code", length=30) private String iataCargoAgentCode;
 @Column(name="agent_account_number", length=120) private String agentAccountNumber;
 @Column(name="first_to_airport", length=3) private String firstToAirport;
 @Column(name="first_by_carrier", length=2) private String firstByCarrier;
 @Column(name="second_to_airport", length=3) private String secondToAirport;
 @Column(name="second_by_carrier", length=2) private String secondByCarrier;
 @Column(name="currency_code", length=3) private String currencyCode;
 @Column(name="payment_terms_code", length=10) private String paymentTermsCode;
 @Column(name="rate_class", length=10) private String rateClass;
 @Column(name="weight_unit", length=2) private String weightUnit = "K";
 @Column(name="rate_per_kg", precision=19, scale=4) private BigDecimal ratePerKg;
 @Column(name="freight_charge", precision=19, scale=4) private BigDecimal freightCharge;
 @Column(name="length_cm", precision=12, scale=2) private BigDecimal lengthCm;
 @Column(name="width_cm", precision=12, scale=2) private BigDecimal widthCm;
 @Column(name="height_cm", precision=12, scale=2) private BigDecimal heightCm;
 @Column(name="volumetric_weight_kg", precision=18, scale=3) private BigDecimal volumetricWeightKg;
 @Column(name="nature_quantity_goods", columnDefinition="text") private String natureQuantityGoods;
 @Column(name="declared_value_carriage", precision=19, scale=4) private BigDecimal declaredValueCarriage;
 @Column(name="carriage_value_code", length=10) private String carriageValueCode;
 @Column(name="declared_value_customs", precision=19, scale=4) private BigDecimal declaredValueCustoms;
 @Column(name="customs_value_code", length=10) private String customsValueCode;
 @Column(name="insurance_amount", precision=19, scale=4) private BigDecimal insuranceAmount;
 @Column(name="routing_segments_json", columnDefinition="text") private String routingSegmentsJson;
 @Column(name="cargo_rating_lines_json", columnDefinition="text") private String cargoRatingLinesJson;

 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=Instant.now();
 protected AwbRecord(){}
 public AwbRecord(UUID t,UUID s,String awb,String type,String mawb,String hawb,String shipper,String shipperAddr,String consignee,String consigneeAddr,String agent,String origin,String dest,Integer pieces,BigDecimal gross,BigDecimal charge,String commodity,String hs,String handling,Boolean dg){tenantId=t;shipmentId=s;awbNumber=awb;awbType=type;mawbNumber=mawb;hawbNumber=hawb;shipperName=shipper;shipperAddress=shipperAddr;consigneeName=consignee;consigneeAddress=consigneeAddr;issuingAgent=agent;originAirport=origin;destinationAirport=dest;this.pieces=pieces;grossWeightKg=gross;chargeableWeightKg=charge;this.commodity=commodity;hsCode=hs;specialHandling=handling;dangerousGoods=dg!=null&&dg;}
 public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getShipmentId(){return shipmentId;} public String getAwbNumber(){return awbNumber;} public String getAwbType(){return awbType;} public String getMawbNumber(){return mawbNumber;} public String getHawbNumber(){return hawbNumber;} public String getShipperName(){return shipperName;} public String getShipperAddress(){return shipperAddress;} public String getConsigneeName(){return consigneeName;} public String getConsigneeAddress(){return consigneeAddress;} public String getIssuingAgent(){return issuingAgent;} public String getOriginAirport(){return originAirport;} public String getDestinationAirport(){return destinationAirport;} public Integer getPieces(){return pieces;} public BigDecimal getGrossWeightKg(){return grossWeightKg;} public BigDecimal getChargeableWeightKg(){return chargeableWeightKg;} public String getCommodity(){return commodity;} public String getHsCode(){return hsCode;} public String getSpecialHandling(){return specialHandling;} public Boolean getDangerousGoods(){return dangerousGoods;} public String getValidationStatus(){return validationStatus;} public String getSubmissionStatus(){return submissionStatus;} public String getCarrierReference(){return carrierReference;} public String getDocumentUri(){return documentUri;} public String getCargoAcceptanceStatus(){return cargoAcceptanceStatus;} public String getEawbStatus(){return eawbStatus;} public String getOneRecordReference(){return oneRecordReference;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
 public void applyExtendedDetails(String airlinePrefix, String airlineSerial,
 String shipperStreetAddress,String shipperPostalCode,String shipperContactName,String shipperPhone,String shipperEmail,String shipperTaxId,String shipperEoriNumber,
 String consigneeStreetAddress,String consigneePostalCode,String consigneeContactName,String consigneePhone,String consigneeEmail,String consigneeTaxId,String consigneeEoriNumber,
 String issuingAgentCity,String iataCargoAgentCode,String agentAccountNumber,String firstToAirport,String firstByCarrier,String secondToAirport,String secondByCarrier,
 String currencyCode,String paymentTermsCode,String rateClass,String weightUnit,BigDecimal ratePerKg,BigDecimal freightCharge,
 BigDecimal lengthCm,BigDecimal widthCm,BigDecimal heightCm,String natureQuantityGoods,BigDecimal declaredValueCarriage,String carriageValueCode,
 BigDecimal declaredValueCustoms,String customsValueCode,BigDecimal insuranceAmount,String routingSegmentsJson,String cargoRatingLinesJson) {
 this.airlinePrefix=blankToNull(airlinePrefix); this.airlineSerial=blankToNull(airlineSerial);
 this.shipperStreetAddress=shipperStreetAddress; this.shipperPostalCode=shipperPostalCode; this.shipperContactName=shipperContactName; this.shipperPhone=shipperPhone; this.shipperEmail=shipperEmail; this.shipperTaxId=shipperTaxId; this.shipperEoriNumber=shipperEoriNumber;
 this.consigneeStreetAddress=consigneeStreetAddress; this.consigneePostalCode=consigneePostalCode; this.consigneeContactName=consigneeContactName; this.consigneePhone=consigneePhone; this.consigneeEmail=consigneeEmail; this.consigneeTaxId=consigneeTaxId; this.consigneeEoriNumber=consigneeEoriNumber;
 this.issuingAgentCity=issuingAgentCity; this.iataCargoAgentCode=blankToNull(iataCargoAgentCode); this.agentAccountNumber=agentAccountNumber;
 if(this.iataCargoAgentCode!=null && !this.iataCargoAgentCode.matches("\\d+")) throw new IllegalArgumentException("IATA cargo agent code must be numeric");
 this.firstToAirport=normalizeCode(firstToAirport,3); this.firstByCarrier=normalizeCarrier(firstByCarrier); this.secondToAirport=normalizeCode(secondToAirport,3); this.secondByCarrier=normalizeCarrier(secondByCarrier);
 this.currencyCode=normalizeCode(currencyCode,3); this.paymentTermsCode=blankToNull(paymentTermsCode)==null?null:paymentTermsCode.trim().toUpperCase(java.util.Locale.ROOT); this.rateClass=blankToNull(rateClass)==null?null:rateClass.trim().toUpperCase(java.util.Locale.ROOT); this.weightUnit=blankToNull(weightUnit)==null?"K":weightUnit.trim().toUpperCase();
 this.ratePerKg=ratePerKg; this.freightCharge=freightCharge; this.lengthCm=lengthCm; this.widthCm=widthCm; this.heightCm=heightCm; this.natureQuantityGoods=natureQuantityGoods;
 this.declaredValueCarriage=declaredValueCarriage; this.carriageValueCode=blankToNull(carriageValueCode); this.declaredValueCustoms=declaredValueCustoms; this.customsValueCode=blankToNull(customsValueCode); this.insuranceAmount=insuranceAmount; this.routingSegmentsJson=routingSegmentsJson; this.cargoRatingLinesJson=cargoRatingLinesJson;
 int dimensionsSupplied=(lengthCm==null?0:1)+(widthCm==null?0:1)+(heightCm==null?0:1);
 if(dimensionsSupplied!=0 && dimensionsSupplied!=3) throw new IllegalArgumentException("Provide all three cargo dimensions (length, width and height) or leave all blank");
 if(dimensionsSupplied==3) {
   if(lengthCm.signum()<=0 || widthCm.signum()<=0 || heightCm.signum()<=0) throw new IllegalArgumentException("Cargo dimensions must be positive");
   this.volumetricWeightKg=lengthCm.multiply(widthCm).multiply(heightCm).divide(new BigDecimal("6000"),3,java.math.RoundingMode.CEILING);
   if(grossWeightKg!=null) this.chargeableWeightKg=grossWeightKg.max(this.volumetricWeightKg);
 }
 if(this.airlinePrefix!=null && !this.airlinePrefix.matches("\\d{3}")) throw new IllegalArgumentException("Airline prefix must be exactly three digits");
 if(this.airlineSerial!=null && !this.airlineSerial.matches("\\d{8}")) throw new IllegalArgumentException("Airline serial must be exactly eight digits");
 if(this.currencyCode!=null && !this.currencyCode.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency must be a three-letter ISO code");
 if(this.paymentTermsCode!=null && !java.util.Set.of("PPD","COLL").contains(this.paymentTermsCode.toUpperCase())) throw new IllegalArgumentException("Payment terms must be PPD or COLL");
 if(this.weightUnit!=null && !java.util.Set.of("K","L").contains(this.weightUnit)) throw new IllegalArgumentException("Weight unit must be K or L");
 if(this.rateClass!=null && !java.util.Set.of("G","C","R","M","N","Q","S").contains(this.rateClass.toUpperCase())) throw new IllegalArgumentException("Unsupported AWB rate class");
 if(this.freightCharge!=null && this.freightCharge.signum()<0 || this.ratePerKg!=null && this.ratePerKg.signum()<0 || this.insuranceAmount!=null && this.insuranceAmount.signum()<0) throw new IllegalArgumentException("Rates and insurance amounts cannot be negative");
 this.updatedAt=Instant.now();
 }
 private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
 private static String normalizeCode(String value,int length){String v=blankToNull(value); if(v==null)return null; v=v.toUpperCase(java.util.Locale.ROOT); if(!v.matches("[A-Z]{"+length+"}")) throw new IllegalArgumentException("Code must contain "+length+" letters"); return v;}
 private static String normalizeCarrier(String value){String v=blankToNull(value); if(v==null)return null; v=v.toUpperCase(java.util.Locale.ROOT); if(!v.matches("[A-Z0-9]{2}")) throw new IllegalArgumentException("IATA carrier designator must contain exactly two letters/digits"); return v;}
 public void validateRecord(){
  if(awbNumber==null||awbNumber.isBlank()||shipmentId==null||originAirport==null||destinationAirport==null||pieces==null||pieces<=0||grossWeightKg==null||grossWeightKg.signum()<=0||chargeableWeightKg==null||chargeableWeightKg.signum()<=0)throw new IllegalArgumentException("AWB requires number, shipment, airports, positive pieces and weights");
  originAirport=originAirport.trim().toUpperCase();destinationAirport=destinationAirport.trim().toUpperCase();
  if(!originAirport.matches("[A-Z]{3}")||!destinationAirport.matches("[A-Z]{3}"))throw new IllegalArgumentException("Origin and destination must be valid 3-letter airport codes");
  if(originAirport.equals(destinationAirport))throw new IllegalArgumentException("AWB origin and destination must differ");
  if(chargeableWeightKg.compareTo(grossWeightKg)<0)throw new IllegalArgumentException("Chargeable weight cannot be lower than gross weight");
  if(awbType==null||!(awbType.equalsIgnoreCase("MAWB")||awbType.equalsIgnoreCase("HAWB")))throw new IllegalArgumentException("awbType must be MAWB or HAWB");
  if("HAWB".equalsIgnoreCase(awbType)&&(mawbNumber==null||mawbNumber.isBlank()))throw new IllegalArgumentException("HAWB requires its parent MAWB number");
  if(mawbNumber!=null&&!mawbNumber.isBlank())mawbNumber=normalizeAndValidateMawb(mawbNumber);
  if("MAWB".equalsIgnoreCase(awbType)) {
   awbNumber=normalizeAndValidateMawb(awbNumber);
   String digits=awbNumber.replace("-", "");
   if(airlinePrefix==null) airlinePrefix=digits.substring(0,3);
   if(airlineSerial==null) airlineSerial=digits.substring(3,11);
   if(!airlinePrefix.equals(digits.substring(0,3)) || !airlineSerial.equals(digits.substring(3,11))) throw new IllegalArgumentException("Airline prefix and serial must match the MAWB number");
  } else {
   hawbNumber=hawbNumber==null||hawbNumber.isBlank()?awbNumber.trim():hawbNumber.trim();
   if(mawbNumber!=null) {
    String parentDigits=mawbNumber.replace("-", "");
    if(airlinePrefix==null) airlinePrefix=parentDigits.substring(0,3);
    if(airlineSerial==null) airlineSerial=parentDigits.substring(3,11);
   }
  }
  validationStatus="VALID";updatedAt=Instant.now();
 }
 private static String normalizeAndValidateMawb(String value){String digits=value.replace("-","").replace(" ","");if(!digits.matches("\\d{11}"))throw new IllegalArgumentException("MAWB must contain 11 digits");int serial=Integer.parseInt(digits.substring(3,10));int check=Integer.parseInt(digits.substring(10));if(serial%7!=check)throw new IllegalArgumentException("MAWB check digit is invalid");return digits.substring(0,3)+"-"+digits.substring(3);}
 public void readyForSubmission(){if(!"VALID".equals(validationStatus))throw new IllegalStateException("AWB must be valid before submission");submissionStatus="READY_FOR_SUBMISSION";eawbStatus="READY";updatedAt=Instant.now();}
 public void submitted(String ref){if(!"VALID".equals(validationStatus))throw new IllegalStateException("AWB must be valid before submission");submissionStatus="SUBMITTED";carrierReference=ref;eawbStatus="SUBMITTED";updatedAt=Instant.now();}
 public void acceptance(String status,String reference,String oneRecord){cargoAcceptanceStatus=status;eawbStatus="ACCEPTED".equalsIgnoreCase(status)?"ACCEPTED":"REJECTED";if(reference!=null&&!reference.isBlank())carrierReference=reference;if(oneRecord!=null&&!oneRecord.isBlank())oneRecordReference=oneRecord;updatedAt=Instant.now();}

 public String getAirlinePrefix(){return airlinePrefix;} public String getAirlineSerial(){return airlineSerial;}
 public String getShipperStreetAddress(){return shipperStreetAddress;} public String getShipperPostalCode(){return shipperPostalCode;} public String getShipperContactName(){return shipperContactName;} public String getShipperPhone(){return shipperPhone;} public String getShipperEmail(){return shipperEmail;} public String getShipperTaxId(){return shipperTaxId;} public String getShipperEoriNumber(){return shipperEoriNumber;}
 public String getConsigneeStreetAddress(){return consigneeStreetAddress;} public String getConsigneePostalCode(){return consigneePostalCode;} public String getConsigneeContactName(){return consigneeContactName;} public String getConsigneePhone(){return consigneePhone;} public String getConsigneeEmail(){return consigneeEmail;} public String getConsigneeTaxId(){return consigneeTaxId;} public String getConsigneeEoriNumber(){return consigneeEoriNumber;}
 public String getIssuingAgentCity(){return issuingAgentCity;} public String getIataCargoAgentCode(){return iataCargoAgentCode;} public String getAgentAccountNumber(){return agentAccountNumber;} public String getFirstToAirport(){return firstToAirport;} public String getFirstByCarrier(){return firstByCarrier;} public String getSecondToAirport(){return secondToAirport;} public String getSecondByCarrier(){return secondByCarrier;}
 public String getCurrencyCode(){return currencyCode;} public String getPaymentTermsCode(){return paymentTermsCode;} public String getRateClass(){return rateClass;} public String getWeightUnit(){return weightUnit;} public BigDecimal getRatePerKg(){return ratePerKg;} public BigDecimal getFreightCharge(){return freightCharge;} public BigDecimal getLengthCm(){return lengthCm;} public BigDecimal getWidthCm(){return widthCm;} public BigDecimal getHeightCm(){return heightCm;} public BigDecimal getVolumetricWeightKg(){return volumetricWeightKg;} public String getNatureQuantityGoods(){return natureQuantityGoods;} public BigDecimal getDeclaredValueCarriage(){return declaredValueCarriage;} public String getCarriageValueCode(){return carriageValueCode;} public BigDecimal getDeclaredValueCustoms(){return declaredValueCustoms;} public String getCustomsValueCode(){return customsValueCode;} public BigDecimal getInsuranceAmount(){return insuranceAmount;} public String getRoutingSegmentsJson(){return routingSegmentsJson;} public String getCargoRatingLinesJson(){return cargoRatingLinesJson;}
}
