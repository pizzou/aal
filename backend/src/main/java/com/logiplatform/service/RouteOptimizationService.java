package com.logiplatform.service;

import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import java.math.BigDecimal; import java.math.RoundingMode; import java.time.Duration; import java.time.Instant; import java.util.*;
@Service
public class RouteOptimizationService {
 private final AirCargoFlightRepository flights;
 public RouteOptimizationService(AirCargoFlightRepository f){flights=f;}
 public List<RouteOption> optimize(String origin,String destination,Instant from,Instant to,BigDecimal weightKg){
  UUID t=TenantContext.getTenantId(); String o=origin.toUpperCase(), d=destination.toUpperCase();
  List<AirCargoFlight> all=flights.findAllByTenantIdAndOriginCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(t,o,from,to);
  List<RouteOption> out=new ArrayList<>();
  for(AirCargoFlight f:all){
   if(f.getAvailableCapacityKg().compareTo(weightKg)<0)continue;
   if(f.getDestinationCode().equalsIgnoreCase(d)) out.add(score(List.of(f),weightKg));
   else {
    Instant minSecond=f.getArrivalTime()==null?f.getDepartureTime().plus(Duration.ofMinutes(60)):f.getArrivalTime().plus(Duration.ofMinutes(60));
    var second=flights.findAllByTenantIdAndOriginCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(t,f.getDestinationCode(),minSecond,to);
    for(AirCargoFlight g:second){if(!g.getDestinationCode().equalsIgnoreCase(d)||g.getAvailableCapacityKg().compareTo(weightKg)<0)continue;out.add(score(List.of(f,g),weightKg));}
   }
  }
  return out.stream().sorted(Comparator.comparingDouble(RouteOption::score)).limit(20).toList();
 }
 private RouteOption score(List<AirCargoFlight> path,BigDecimal weightKg){
  AirCargoFlight first=path.get(0),last=path.get(path.size()-1);long mins=last.getArrivalTime()==null?0:Duration.between(first.getDepartureTime(),last.getArrivalTime()).toMinutes();
  double scarcity=path.stream().mapToDouble(f->f.getTotalCapacityKg().signum()==0?1:f.getAvailableCapacityKg().divide(f.getTotalCapacityKg(),6,RoundingMode.HALF_UP).doubleValue()).average().orElse(0);
  double score=mins+(1-scarcity)*240+(path.size()-1)*180;
  String flightNumbers=path.stream().map(AirCargoFlight::getFlightNumber).reduce((a,b)->a+" + "+b).orElse("");
  String carriers=path.stream().map(f->f.getCarrierName()==null?f.getCarrierCode():f.getCarrierName()).reduce((a,b)->a+" + "+b).orElse("");
  return new RouteOption(first.getId(),first.getCarrierCode(),carriers,flightNumbers,first.getOriginCode(),last.getDestinationCode(),first.getDepartureTime(),last.getArrivalTime(),path.stream().map(AirCargoFlight::getAvailableCapacityKg).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO),score);
 }
 public record RouteOption(UUID flightId,String carrierCode,String carrierName,String flightNumber,String origin,String destination,Instant departure,Instant arrival,BigDecimal availableCapacityKg,double score){}
}
