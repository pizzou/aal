package com.logiplatform.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(name="flightstatus.aviationstack.enabled",havingValue="true")
public class AviationStackFlightStatusAdapter implements FlightStatusPort {
    private static final int SIGNIFICANT_DELAY_THRESHOLD_MINUTES=60;
    private final RestTemplate restTemplate; private final String baseUrl; private final String apiKey;
    public AviationStackFlightStatusAdapter(RestTemplate restTemplate,@Value("${flightstatus.aviationstack.base-url:https://api.aviationstack.com/v1}") String baseUrl,@Value("${flightstatus.aviationstack.api-key:}") String apiKey){this.restTemplate=restTemplate;this.baseUrl=baseUrl.replaceAll("/+$","");this.apiKey=apiKey;}
    @Override public Optional<FlightStatusResult> getStatus(String flightIataCode,String flightDate){
        String url=baseUrl+"/flights?access_key="+apiKey+"&flight_iata="+flightIataCode+(flightDate==null?"":"&flight_date="+flightDate);
        AviationStackResponse response; try{response=restTemplate.getForObject(url,AviationStackResponse.class);}catch(Exception e){return Optional.empty();}
        if(response==null||response.data==null||response.data.isEmpty())return Optional.empty();
        FlightData f=response.data.get(0); int dep=f.departure!=null&&f.departure.delay!=null?f.departure.delay:0; int arr=f.arrival!=null&&f.arrival.delay!=null?f.arrival.delay:0;
        return Optional.of(new FlightStatusResult(f.flight_status,dep,arr,dep>=SIGNIFICANT_DELAY_THRESHOLD_MINUTES||arr>=SIGNIFICANT_DELAY_THRESHOLD_MINUTES,
                instant(f.departure==null?null:f.departure.scheduled),instant(f.departure==null?null:f.departure.estimated),instant(f.departure==null?null:f.departure.actual),
                instant(f.arrival==null?null:f.arrival.scheduled),instant(f.arrival==null?null:f.arrival.estimated),instant(f.arrival==null?null:f.arrival.actual),
                f.flight==null?null:f.flight.iata,response.toString()));
    }
    private static Instant instant(String v){if(v==null||v.isBlank())return null;try{return Instant.parse(v);}catch(Exception e){return null;}}
    @JsonIgnoreProperties(ignoreUnknown=true) private static class AviationStackResponse {public List<FlightData> data;}
    @JsonIgnoreProperties(ignoreUnknown=true) private static class FlightData {public String flight_status;public DepartureArrival departure;public DepartureArrival arrival;public Flight flight;}
    @JsonIgnoreProperties(ignoreUnknown=true) private static class Flight {public String iata;}
    @JsonIgnoreProperties(ignoreUnknown=true) private static class DepartureArrival {public Integer delay;public String scheduled;public String estimated;public String actual;}
}
