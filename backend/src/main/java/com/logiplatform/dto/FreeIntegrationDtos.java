package com.logiplatform.dto;
import java.util.List;
public final class FreeIntegrationDtos { private FreeIntegrationDtos(){}
 public record Provider(String code,String name,String kind,boolean configured,String baseUrl,String note){}
 public record WeatherResponse(double latitude,double longitude,String timezone,double temperatureC,double windSpeedKmh,int weatherCode){}
 public record RouteResponse(double distanceKm,int durationMinutes,List<List<Double>> geometry){}
 public record FlightSearchRequest(String origin,String destination,String departureDate,String adults){}
 public record FlightSearchResponse(Object data) {}
}
