package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Service
public class ExternalGatewayService {
    private final RestTemplate rest;
    private final String carrierUrl,customsUrl,brokerUrl,apiKey;

    public ExternalGatewayService(RestTemplate r,
        @Value("${aircargo.carrier.base-url:}") String c,
        @Value("${aircargo.customs.base-url:}") String customs,
        @Value("${aircargo.customs.broker-url:}") String broker,
        @Value("${aircargo.integration.api-key:}") String key){
        rest=r;carrierUrl=strip(c);customsUrl=strip(customs);brokerUrl=strip(broker);apiKey=key;
    }

    public boolean carrierConfigured(){return !carrierUrl.isBlank();}

    public Map<String,Object> getSchedules(String origin,String destination,java.time.Instant from,java.time.Instant to){
        return get(carrierUrl,"/schedules",Map.of("origin",origin,"destination",destination,"from",from.toString(),"to",to.toString()));
    }
    public Map<String,Object> getCapacity(String flightNumber,java.time.Instant date){
        return get(carrierUrl,"/capacity",Map.of("flightNumber",flightNumber,"date",date.toString()));
    }
    public Map<String,Object> submitBooking(Map<String,Object> payload){return post(carrierUrl,"/bookings",payload,"Carrier booking endpoint is not configured",String.valueOf(payload.getOrDefault("idempotencyKey",UUID.randomUUID())));}
    public Map<String,Object> submitAwb(Map<String,Object> payload){return post(carrierUrl,"/awb",payload,"Carrier AWB endpoint is not configured",String.valueOf(payload.getOrDefault("idempotencyKey",UUID.randomUUID())));}
    public Map<String,Object> submitCustoms(Map<String,Object> payload){
        String url=!customsUrl.isBlank()?customsUrl:brokerUrl;
        return post(url,"/submissions",payload,"Customs or broker endpoint is not configured",String.valueOf(payload.getOrDefault("idempotencyKey",UUID.randomUUID())));
    }

    private Map<String,Object> get(String base,String path,Map<String,String> params){
        if(base==null||base.isBlank())throw new IllegalStateException("Carrier endpoint is not configured");
        StringBuilder u=new StringBuilder(base).append(path).append("?");
        params.forEach((k,v)->u.append(java.net.URLEncoder.encode(k,java.nio.charset.StandardCharsets.UTF_8))
            .append("=").append(java.net.URLEncoder.encode(v,java.nio.charset.StandardCharsets.UTF_8)).append("&"));
        HttpHeaders h=headers();
        ResponseEntity<Map> response=rest.exchange(u.toString(),HttpMethod.GET,new HttpEntity<>(h),Map.class);
        return response.getBody()==null?Map.of():response.getBody();
    }
    private Map<String,Object> post(String base,String path,Map<String,Object> body,String msg,String idempotencyKey){
        if(base==null||base.isBlank())throw new IllegalStateException(msg);
        HttpHeaders h=headers(); h.set("Idempotency-Key",idempotencyKey); ResponseEntity<Map> r=rest.exchange(base+path,HttpMethod.POST,new HttpEntity<>(body,h),Map.class);
        return r.getBody()==null?Map.of():r.getBody();
    }
    private HttpHeaders headers(){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(apiKey!=null&&!apiKey.isBlank())h.setBearerAuth(apiKey);return h;}
    private static String strip(String s){if(s==null)return "";return s.trim().replaceAll("/+$","");}
}
