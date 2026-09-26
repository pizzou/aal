package com.logiplatform.service;

import com.logiplatform.dto.AirCargoDtos;
import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class AirCargoConnectivityService {
    private final AirCargoFlightRepository repo; private final AirCargoProviderRegistry providers;
    public AirCargoConnectivityService(AirCargoFlightRepository r,AirCargoProviderRegistry p){repo=r;providers=p;}

    public AirCargoFlight ingest(AirCargoDtos.FlightIngestRequest r){
        UUID t=TenantContext.getTenantId();
        var existing=repo.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(t,r.originCode().toUpperCase(),r.destinationCode().toUpperCase(),r.departureTime().minusSeconds(1),r.departureTime().plusSeconds(1)).stream().filter(f->f.getCarrierCode().equalsIgnoreCase(r.carrierCode())&&f.getFlightNumber().equalsIgnoreCase(r.flightNumber())).findFirst().orElse(null);
        if(existing!=null){existing.refreshCapacity(r.totalCapacityKg(),r.availableCapacityKg(),r.arrivalTime(),"INGESTED");return repo.save(existing);}
        return repo.save(new AirCargoFlight(t,r.carrierCode(),r.carrierName(),r.flightNumber(),r.originCode(),r.destinationCode(),r.departureTime(),r.arrivalTime(),r.totalCapacityKg(),r.availableCapacityKg(),"INGESTED"));
    }

    public List<AirCargoFlight> schedules(String origin,String destination,Instant from,Instant to,BigDecimal weightKg){
        UUID t=TenantContext.getTenantId(); String o=origin==null?"":origin.trim().toUpperCase();String d=destination==null?"":destination.trim().toUpperCase();
        if(!o.matches("[A-Z]{3}")||!d.matches("[A-Z]{3}"))throw new IllegalArgumentException("Origin and destination must be valid 3-letter airport codes");
        if(o.equals(d))throw new IllegalArgumentException("Origin and destination must differ");if(from==null||to==null||!to.isAfter(from))throw new IllegalArgumentException("Flight search end time must be after start time");if(weightKg==null||weightKg.signum()<=0)throw new IllegalArgumentException("weightKg must be positive");
        AirCargoProviderPort p=providers.active();
        if(p.capabilities().scheduleSearch()){
            List<AirCargoFlight> result=new ArrayList<>();
            for(var f:p.searchFlights(o,d,from,to,weightKg)){
                var existing=repo.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(t,o,d,f.departure().minusSeconds(1),f.departure().plusSeconds(1)).stream().filter(x->x.getCarrierCode().equalsIgnoreCase(f.carrierCode())&&x.getFlightNumber().equalsIgnoreCase(f.flightNumber())).findFirst().orElse(null);
                if(existing!=null){existing.refreshCapacity(f.totalCapacityKg(),f.availableCapacityKg(),f.arrival(),p.providerCode());result.add(repo.save(existing));}
                else result.add(repo.save(new AirCargoFlight(t,f.carrierCode(),f.carrierName(),f.flightNumber(),f.origin(),f.destination(),f.departure(),f.arrival(),f.totalCapacityKg(),f.availableCapacityKg(),p.providerCode())));
            }
            return result;
        }
        return repo.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(t,o,d,from,to).stream().filter(f->f.getAvailableCapacityKg().compareTo(weightKg)>=0).toList();
    }

    public CapacityResponse capacity(String flightNumber,Instant date){
        AirCargoProviderPort p=providers.active();
        if(p.capabilities().liveCapacity()){var c=p.capacity(flightNumber,date);return new CapacityResponse(c.flightNumber(),c.availableCapacityKg(),c.totalCapacityKg(),c.asOf(),c.source());}
        List<AirCargoFlight> local=repo.findAllByTenantIdAndFlightNumberAndDepartureTimeBetweenOrderByDepartureTimeAsc(TenantContext.getTenantId(),flightNumber,date.minusSeconds(1),date.plusSeconds(86400));
        var f=local.stream().filter(x->x.getFlightNumber().equalsIgnoreCase(flightNumber)).findFirst().orElse(null);return f==null?new CapacityResponse(flightNumber,BigDecimal.ZERO,BigDecimal.ZERO,Instant.now(),"NOT_CONFIGURED"):new CapacityResponse(flightNumber,f.getAvailableCapacityKg(),f.getTotalCapacityKg(),Instant.now(),f.getSource());
    }
    public record CapacityResponse(String flightNumber,BigDecimal availableCapacityKg,BigDecimal totalCapacityKg,Instant asOf,String source){}
}
