package com.logiplatform.integration.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** OAuth2 client-credentials lifecycle for provider adapters. Tokens stay in memory and expire before reuse. */
@Service
public class IntegrationOAuth2TokenService {
    private final RestTemplate rest; private final ObjectMapper mapper; private final IntegrationCredentialService credentials; private final Map<UUID,Token> cache=new ConcurrentHashMap<>();
    public IntegrationOAuth2TokenService(RestTemplate rest,ObjectMapper mapper,IntegrationCredentialService credentials){this.rest=rest;this.mapper=mapper;this.credentials=credentials;}
    public String token(UUID accountId,String tokenUrl,String clientIdCredentialType,String clientSecretCredentialType,String scope){Token cached=cache.get(accountId);if(cached!=null&&Instant.now().isBefore(cached.expiresAt().minusSeconds(30)))return cached.value();String clientId=credentials.resolveActiveSecret(accountId,clientIdCredentialType).orElseThrow(()->new IllegalStateException("OAuth2 client-id credential is not active"));String clientSecret=credentials.resolveActiveSecret(accountId,clientSecretCredentialType).orElseThrow(()->new IllegalStateException("OAuth2 client-secret credential is not active"));HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);MultiValueMap<String,String> form=new LinkedMultiValueMap<>();form.add("grant_type","client_credentials");form.add("client_id",clientId);form.add("client_secret",clientSecret);if(scope!=null&&!scope.isBlank())form.add("scope",scope);ResponseEntity<String> response=rest.postForEntity(tokenUrl,new HttpEntity<>(form,h),String.class);if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("OAuth2 token endpoint rejected credentials");try{JsonNode n=mapper.readTree(response.getBody()==null?"{}":response.getBody());String value=n.path("access_token").asText("");if(value.isBlank())throw new IllegalStateException("OAuth2 token response missing access_token");long expires=Math.max(60,n.path("expires_in").asLong(300));Token result=new Token(value,Instant.now().plusSeconds(expires));cache.put(accountId,result);return value;}catch(Exception ex){throw new IllegalStateException("Invalid OAuth2 token response",ex);}}
    public void evict(UUID accountId){cache.remove(accountId);}
    private record Token(String value,Instant expiresAt){}
}
