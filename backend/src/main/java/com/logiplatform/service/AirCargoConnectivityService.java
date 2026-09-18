package com.logiplatform.service;

import com.logiplatform.dto.AirCargoDtos;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class AirCargoConnectivityService {
    private final AirCargoFlightRepository repo; private final ExternalGatewayService external; private final ObjectMapper mapper;
    private final String baseUrl;
    public AirCargoConnectivityService(AirCargoFlightRepository r,ExternalGatewayService e,ObjectMapper m,
        @Value("${aircargo.carrier.base-url:}") String base){repo=r;external=e;mapper=m;baseUrl=base==null?"":base;}

    public AirCargoFlight ingest(AirCargoDtos.FlightIngestRequest r){
        UUID t=TenantContext.getTenantId();
        var existing=repo.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            t,r.originCode().toUpperCase(),r.destinationCode().toUpperCase(),r.departureTime().minusSeconds(1),r.departureTime().plusSeconds(1))
            .stream().filter(f->f.getCarrierCode().equalsIgnoreCase(r.carrierCode())&&f.getFlightNumber().equalsIgnoreCase(r.flightNumber())).findFirst().orElse(null);
        if(existing!=null){existing.refreshCapacity(r.totalCapacityKg(),r.availableCapacityKg(),r.arrivalTime(),"INGESTED");return repo.save(existing);}
        return repo.save(new AirCargoFlight(t,r.carrierCode(),r.carrierName(),r.flightNumber(),r.originCode(),r.destinationCode(),
            r.departureTime(),r.arrivalTime(),r.totalCapacityKg(),r.availableCapacityKg(),"INGESTED"));
    }

    public List<AirCargoFlight> schedules(String origin,String destination,Instant from,Instant to,BigDecimal weightKg){
        UUID t=TenantContext.getTenantId();
        String o = origin == null ? "" : origin.trim().toUpperCase();
        String d = destination == null ? "" : destination.trim().toUpperCase();
        if(!o.matches("[A-Z]{3}") || !d.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Origin and destination must be valid 3-letter airport codes");
        if(o.equals(d)) throw new IllegalArgumentException("Origin and destination must differ");
        if(from == null || to == null || !to.isAfter(from))
            throw new IllegalArgumentException("Flight search end time must be after start time");
        if(weightKg == null || weightKg.signum() <= 0)
            throw new IllegalArgumentException("weightKg must be positive");
        if(baseUrl.isBlank()) return repo.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
            t,o,d,from,to).stream()
            .filter(f -> f.getAvailableCapacityKg().compareTo(weightKg) >= 0)
            .toList();
        Map<String,Object> raw=external.getSchedules(o,d,from,to);
        Object items=raw.getOrDefault("flights",raw.getOrDefault("data",List.of()));
        List<AirCargoFlight> result=new ArrayList<>();
        if(items instanceof Collection<?> c) for(Object item:c){
            ExternalFlight f=mapper.convertValue(item,ExternalFlight.class);
            if(f.carrierCode()==null||f.flightNumber()==null||f.originCode()==null||f.destinationCode()==null
                ||f.departureTime()==null||f.totalCapacityKg()==null||f.availableCapacityKg()==null)
                throw new IllegalStateException("Carrier returned an incomplete flight record");
            if(f.availableCapacityKg().compareTo(weightKg) < 0) continue;
            result.add(repo.save(new AirCargoFlight(t,f.carrierCode(),f.carrierName(),f.flightNumber(),
                f.originCode(),f.destinationCode(),f.departureTime(),f.arrivalTime(),
                f.totalCapacityKg(),f.availableCapacityKg(),"CARRIER_API")));
        }
        return result;
    }

    public CapacityResponse capacity(String flightNumber,Instant date){
        if(baseUrl.isBlank())return new CapacityResponse(flightNumber,BigDecimal.ZERO,BigDecimal.ZERO,Instant.now(),"NOT_CONFIGURED");
        Map<String,Object> raw=external.getCapacity(flightNumber,date);
        return new CapacityResponse(flightNumber,decimal(raw.get("availableCapacityKg")),decimal(raw.get("totalCapacityKg")),
            Instant.now(),"CARRIER_API");
    }
    private record ExternalFlight(String carrierCode,String carrierName,String flightNumber,String originCode,
                                  String destinationCode,Instant departureTime,Instant arrivalTime,
                                  BigDecimal totalCapacityKg,BigDecimal availableCapacityKg,String status){}
    private static BigDecimal decimal(Object x){return x==null?BigDecimal.ZERO:new BigDecimal(String.valueOf(x));}
    public record CapacityResponse(String flightNumber,BigDecimal availableCapacityKg,BigDecimal totalCapacityKg,Instant asOf,String source){}
}
