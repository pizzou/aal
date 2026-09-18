package com.logiplatform.service;

import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;


import com.logiplatform.tenancy.TenantContext; import org.springframework.stereotype.Service; import java.time.*; import java.util.*;
@Service public class PredictiveMilestoneService {
 private final ShipmentRepository shipments; private final FlightStatusPort flightStatus;
 public PredictiveMilestoneService(ShipmentRepository s,FlightStatusPort f){shipments=s;flightStatus=f;}
 public Prediction predict(UUID shipmentId){var s=shipments.findByIdAndTenantId(shipmentId,TenantContext.getTenantId()).orElseThrow(()->new IllegalArgumentException("Shipment not found"));if(s.getFlightNumber()==null||s.getFlightNumber().isBlank())throw new IllegalArgumentException("Shipment has no flight number");String date=LocalDateTime.ofInstant(s.getEtd()==null?Instant.now():s.getEtd(),ZoneOffset.UTC).toLocalDate().toString();var result=flightStatus.getStatus(s.getFlightNumber(),date).orElse(null);int delay=result==null?0:result.arrivalDelayMinutes();Instant predicted=s.getEta()==null?null:s.getEta().plusSeconds(delay*60L);return new Prediction(shipmentId,s.getFlightNumber(),s.getEta(),predicted,delay,result==null?"NO_LIVE_FLIGHT_DATA":result.flightStatus(),result!=null&&result.significantDelay());}
 public record Prediction(UUID shipmentId,String flightNumber,Instant scheduledEta,Instant predictedEta,int arrivalDelayMinutes,String flightStatus,boolean significantDelay){}
}
