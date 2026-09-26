package com.logiplatform.integration.onerecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

@Component
public class OneRecordAuthentication {
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final String tokenUrl, clientId, clientSecret, scope;
    private volatile String token;
    private volatile Instant expiresAt;

    public OneRecordAuthentication(RestTemplate rest,ObjectMapper mapper,
                                   @Value("${onerecord.token-url:}") String tokenUrl,
                                   @Value("${onerecord.client-id:}") String clientId,
                                   @Value("${onerecord.client-secret:}") String clientSecret,
                                   @Value("${onerecord.scope:}") String scope){this.rest=rest;this.mapper=mapper;this.tokenUrl=tokenUrl;this.clientId=clientId;this.clientSecret=clientSecret;this.scope=scope;}

    public synchronized String accessToken(){
        if(token!=null&&expiresAt!=null&&Instant.now().isBefore(expiresAt.minusSeconds(30))) return token;
        if(tokenUrl.isBlank()||clientId.isBlank()||clientSecret.isBlank()) throw new IllegalStateException("ONE Record OAuth2 credentials are not configured");
        HttpHeaders h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_FORM_URLENCODED); MultiValueMap<String,String> form=new LinkedMultiValueMap<>(); form.add("grant_type","client_credentials"); form.add("client_id",clientId); form.add("client_secret",clientSecret); if(!scope.isBlank()) form.add("scope",scope);
        ResponseEntity<String> r=rest.postForEntity(tokenUrl,new HttpEntity<>(form,h),String.class); if(!r.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("ONE Record token request failed");
        try{JsonNode n=mapper.readTree(r.getBody()==null?"{}":r.getBody()); token=n.path("access_token").asText(""); long seconds=Math.max(60,n.path("expires_in").asLong(300)); expiresAt=Instant.now().plusSeconds(seconds); if(token.isBlank()) throw new IllegalStateException("ONE Record token response missing access_token"); return token;}catch(Exception ex){throw new IllegalStateException("Invalid ONE Record token response",ex);}
    }
}
