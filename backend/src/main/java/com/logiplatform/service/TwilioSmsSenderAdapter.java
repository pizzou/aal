package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "sms.twilio.enabled", havingValue = "true")
public class TwilioSmsSenderAdapter implements SmsSenderPort {
    private final RestTemplate rest;
    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final String baseUrl;

    public TwilioSmsSenderAdapter(
            RestTemplate rest,
            @Value("${sms.twilio.account-sid:}") String accountSid,
            @Value("${sms.twilio.auth-token:}") String authToken,
            @Value("${sms.twilio.from-number:}") String fromNumber,
            @Value("${sms.twilio.base-url:https://api.twilio.com}") String baseUrl) {
        this.rest = rest;
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public SendResult send(String recipient, String message) {
        if (accountSid.isBlank() || authToken.isBlank() || fromNumber.isBlank()) {
            return new SendResult(false, "TWILIO", null, "Twilio SMS credentials are not configured");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(accountSid, authToken);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String,String> form = new LinkedMultiValueMap<>();
            form.add("To", recipient);
            form.add("From", fromNumber);
            form.add("Body", message);
            ResponseEntity<Map<String,Object>> response = rest.exchange(
                    baseUrl + "/2010-04-01/Accounts/" + accountSid + "/Messages.json",
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    new ParameterizedTypeReference<>() {});
            if (!response.getStatusCode().is2xxSuccessful()) {
                return new SendResult(false, "TWILIO", null, "Twilio returned " + response.getStatusCode().value());
            }
            Map<String,Object> body = response.getBody();
            String sid = body == null ? null : String.valueOf(body.get("sid"));
            return new SendResult(true, "TWILIO", sid, null);
        } catch (Exception e) {
            return new SendResult(false, "TWILIO", null, e.getMessage());
        }
    }
}
