package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ExternalGatewayService {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestTemplate rest;
    private final String carrierUrl;
    private final String customsUrl;
    private final String brokerUrl;
    private final String apiKey;

    public ExternalGatewayService(
            RestTemplate rest,
            @Value("${aircargo.carrier.base-url:}") String carrierUrl,
            @Value("${aircargo.customs.base-url:}") String customsUrl,
            @Value("${aircargo.customs.broker-url:}") String brokerUrl,
            @Value("${aircargo.integration.api-key:}") String apiKey) {
        this.rest = rest;
        this.carrierUrl = strip(carrierUrl);
        this.customsUrl = strip(customsUrl);
        this.brokerUrl = strip(brokerUrl);
        this.apiKey = apiKey;
    }

    public boolean carrierConfigured() {
        return !carrierUrl.isBlank();
    }

    public Map<String, Object> getSchedules(String origin, String destination, Instant from, Instant to) {
        return get(carrierUrl, "/schedules", Map.of(
                "origin", origin,
                "destination", destination,
                "from", from.toString(),
                "to", to.toString()));
    }

    public Map<String, Object> getCapacity(String flightNumber, Instant date) {
        return get(carrierUrl, "/capacity", Map.of(
                "flightNumber", flightNumber,
                "date", date.toString()));
    }

    public Map<String, Object> submitBooking(Map<String, Object> payload) {
        return post(carrierUrl, "/bookings", payload,
                "Carrier booking endpoint is not configured", idempotencyKey(payload));
    }

    public Map<String, Object> submitAwb(Map<String, Object> payload) {
        return post(carrierUrl, "/awb", payload,
                "Carrier AWB endpoint is not configured", idempotencyKey(payload));
    }

    public Map<String, Object> submitCustoms(Map<String, Object> payload) {
        String url = !customsUrl.isBlank() ? customsUrl : brokerUrl;
        return post(url, "/submissions", payload,
                "Customs or broker endpoint is not configured", idempotencyKey(payload));
    }

    private Map<String, Object> get(String base, String path, Map<String, String> params) {
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("Carrier endpoint is not configured");
        }

        StringBuilder url = new StringBuilder(strip(base)).append(path).append('?');
        params.forEach((key, value) -> url
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                .append('&'));

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url.toString(), HttpMethod.GET, new HttpEntity<>(headers()), MAP_TYPE);
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private Map<String, Object> post(
            String base,
            String path,
            Map<String, Object> body,
            String notConfiguredMessage,
            String idempotencyKey) {
        if (base == null || base.isBlank()) {
            throw new IllegalStateException(notConfiguredMessage);
        }

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpHeaders headers = headers();
                headers.set("Idempotency-Key", idempotencyKey);
                ResponseEntity<Map<String, Object>> response = rest.exchange(
                        strip(base) + path,
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        MAP_TYPE);

                if (response.getStatusCode().is2xxSuccessful()) {
                    return response.getBody() == null ? Map.of() : response.getBody();
                }
                last = new IllegalStateException(
                        "External integration returned HTTP " + response.getStatusCode().value());
            } catch (RuntimeException ex) {
                last = ex;
            }

            try {
                Thread.sleep(250L * attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("External integration retry interrupted", ex);
            }
        }

        throw new IllegalStateException("External integration failed after 3 attempts", last);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        return headers;
    }

    private static String idempotencyKey(Map<String, Object> payload) {
        Object key = payload.get("idempotencyKey");
        return key == null || String.valueOf(key).isBlank()
                ? UUID.randomUUID().toString()
                : String.valueOf(key);
    }

    private static String strip(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}
