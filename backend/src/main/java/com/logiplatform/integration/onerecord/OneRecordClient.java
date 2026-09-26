package com.logiplatform.integration.onerecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class OneRecordClient {
    private final RestTemplate rest; private final ObjectMapper mapper; private final OneRecordAuthentication auth; private final String baseUrl,apiVersion;
    public OneRecordClient(RestTemplate rest,ObjectMapper mapper,OneRecordAuthentication auth,@Value("${onerecord.base-url:}") String baseUrl,@Value("${onerecord.api-version:2.3.0}") String apiVersion){this.rest=rest;this.mapper=mapper;this.auth=auth;this.baseUrl=baseUrl==null?"":baseUrl.replaceAll("/+$","");this.apiVersion=apiVersion;}
    public JsonNode get(String path){return exchange(HttpMethod.GET,path,null).body();}
    public JsonNode post(String path,Object body){return exchange(HttpMethod.POST,path,body).body();}
    public JsonNode patch(String path,Object body){return exchange(HttpMethod.PATCH,path,body).body();}
    public JsonNode delete(String path){return exchange(HttpMethod.DELETE,path,null).body();}
    public String apiVersion(){return apiVersion;}
    private Result exchange(HttpMethod method,String path,Object body){if(baseUrl.isBlank()) throw new IllegalStateException("ONE Record base URL is not configured"); HttpHeaders h=new HttpHeaders(); h.setBearerAuth(auth.accessToken()); h.setAccept(List.of(MediaType.APPLICATION_JSON)); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-ONE-Record-API-Version",apiVersion); ResponseEntity<String> r=rest.exchange(baseUrl+(path.startsWith("/")?path:"/"+path),method,new HttpEntity<>(body,h),String.class); if(!r.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("ONE Record request failed with HTTP "+r.getStatusCode().value()); try{return new Result(r.getStatusCode(),mapper.readTree(r.getBody()==null?"{}":r.getBody()));}catch(Exception ex){throw new IllegalStateException("ONE Record response is not valid JSON",ex);}}
    private record Result(HttpStatusCode status,JsonNode body){}
}
