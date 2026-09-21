package com.logiplatform.service;

import com.logiplatform.dto.FreeIntegrationDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class FreeIntegrationService {
    private final RestTemplate rest;
    private final String traccar, traccarUser, traccarPassword, amadeusBase, amadeusId, amadeusSecret,
            paypalBase, paypalId, paypalSecret, valhalla, weather;

    public FreeIntegrationService(
            RestTemplate r,
            @Value("${traccar.api-base-url:${TRACCAR_API_BASE_URL:http://localhost:8082/api}}") String traccar,
            @Value("${traccar.username:${TRACCAR_USERNAME:}}") String traccarUser,
            @Value("${traccar.password:${TRACCAR_PASSWORD:}}") String traccarPassword,
            @Value("${amadeus.base-url:${AMADEUS_BASE_URL:https://test.api.amadeus.com}}") String ab,
            @Value("${amadeus.client-id:${AMADEUS_CLIENT_ID:}}") String ai,
            @Value("${amadeus.client-secret:${AMADEUS_CLIENT_SECRET:}}") String as,
            @Value("${paypal.base-url:${PAYPAL_BASE_URL:https://api-m.sandbox.paypal.com}}") String pb,
            @Value("${paypal.client-id:${PAYPAL_CLIENT_ID:}}") String pi,
            @Value("${paypal.client-secret:${PAYPAL_CLIENT_SECRET:}}") String ps,
            @Value("${valhalla.base-url:${VALHALLA_BASE_URL:http://localhost:8002}}") String v,
            @Value("${open-meteo.base-url:${OPEN_METEO_BASE_URL:https://api.open-meteo.com/v1/forecast}}") String w) {
        rest = r;
        this.traccar = strip(traccar);
        this.traccarUser = traccarUser;
        this.traccarPassword = traccarPassword;
        amadeusBase = strip(ab);
        amadeusId = ai;
        amadeusSecret = as;
        paypalBase = strip(pb);
        paypalId = pi;
        paypalSecret = ps;
        valhalla = strip(v);
        weather = strip(w);
    }

    public List<Provider> providers() {
        return List.of(
                new Provider("TRACCAR", "Traccar GPS", "GPS",
                        !traccar.isBlank() && !traccarUser.isBlank() && !traccarPassword.isBlank(),
                        traccar, "Free/open-source when self-hosted; use a device mapping per vehicle"),
                new Provider("AMADEUS", "Amadeus Flight APIs", "AIR",
                        !amadeusId.isBlank() && !amadeusSecret.isBlank(),
                        amadeusBase, "Free monthly request quota; over-quota production usage is billed"),
                new Provider("PAYPAL_SANDBOX", "PayPal Sandbox", "PAYMENTS",
                        !paypalId.isBlank() && !paypalSecret.isBlank(),
                        paypalBase, "Free sandbox for end-to-end payment testing"),
                new Provider("VALHALLA", "Valhalla Routing", "ROUTING", true,
                        valhalla, "Open-source; self-host for commercial production"),
                new Provider("OPEN_METEO", "Open-Meteo", "WEATHER", true,
                        weather, "Use self-hosting for commercial production; public free tier is non-commercial"),
                new Provider("DCSA_SANDBOX", "DCSA Standards Sandbox", "OCEAN", true,
                        "", "Use the DCSA conformance sandbox or a carrier endpoint"),
                new Provider("ERPNEXT", "ERPNext/Frappe", "ACCOUNTING", true,
                        "", "Self-hosted open-source deployment exposes REST APIs"),
                new Provider("WHATSAPP_CLOUD", "WhatsApp Cloud API", "MESSAGING", false,
                        "https://graph.facebook.com", "Official API; production messaging is subject to Meta pricing/policy"));
    }

    public Map<String, Object> weather(double lat, double lon) {
        String u = weather + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,wind_speed_10m,weather_code&timezone=auto";
        Map<String, Object> body = getMap(u);
        Map<String, Object> current = asMap(body.get("current"));

        return Map.of(
                "latitude", lat,
                "longitude", lon,
                "timezone", String.valueOf(body.getOrDefault("timezone", "UTC")),
                "temperatureC", num(current.get("temperature_2m")),
                "windSpeedKmh", num(current.get("wind_speed_10m")),
                "weatherCode", number(current.getOrDefault("weather_code", 0)).intValue());
    }

    public Map<String, Object> route(double fromLat, double fromLon, double toLat, double toLon) {
        String u = valhalla + "/route";
        Map<String, Object> request = Map.of(
                "locations", List.of(
                        Map.of("lat", fromLat, "lon", fromLon),
                        Map.of("lat", toLat, "lon", toLon)),
                "costing", "auto",
                "units", "kilometers");

        Map<String, Object> response = postMap(u, request);
        double dist = 0;
        int mins = 0;
        List<List<Double>> geom = List.of();

        Map<String, Object> trip = asMap(response.get("trip"));
        Map<String, Object> summary = asMap(trip.get("summary"));

        if (!summary.isEmpty()) {
            dist = num(summary.get("length"));
            mins = (int) Math.round(num(summary.get("time")) / 60.0);
        }

        return Map.of(
                "distanceKm", dist,
                "durationMinutes", mins,
                "geometry", geom);
    }

    public Map<String, Object> amadeusToken() {
        if (amadeusId.isBlank() || amadeusSecret.isBlank()) {
            throw new IllegalStateException("Amadeus credentials are not configured");
        }

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.MultiValueMap<String, String> f =
                new org.springframework.util.LinkedMultiValueMap<>();
        f.add("grant_type", "client_credentials");
        f.add("client_id", amadeusId);
        f.add("client_secret", amadeusSecret);

        return postMap(amadeusBase + "/v1/security/oauth2/token", new HttpEntity<>(f, h));
    }

    public Map<String, Object> amadeusFlightSearch(
            String origin, String destination, String departureDate, String adults) {
        Map<String, Object> token = amadeusToken();
        String access = String.valueOf(token.get("access_token"));
        String u = amadeusBase + "/v2/shopping/flight-offers?originLocationCode=" + enc(origin)
                + "&destinationLocationCode=" + enc(destination)
                + "&departureDate=" + enc(departureDate)
                + "&adults=" + enc(adults == null || adults.isBlank() ? "1" : adults)
                + "&max=10";

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                u,
                HttpMethod.GET,
                new HttpEntity<>(h),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        return response.getBody() == null ? Map.of() : response.getBody();
    }

    public Map<String, Object> paypalToken() {
        if (paypalId.isBlank() || paypalSecret.isBlank()) {
            throw new IllegalStateException("PayPal sandbox credentials are not configured");
        }

        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(paypalId, paypalSecret);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        org.springframework.util.MultiValueMap<String, String> f =
                new org.springframework.util.LinkedMultiValueMap<>();
        f.add("grant_type", "client_credentials");

        return postMap(paypalBase + "/v1/oauth2/token", new HttpEntity<>(f, h));
    }

    private Map<String, Object> getMap(String url) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {});
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private Map<String, Object> postMap(String url, Object requestBody) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(requestBody),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Number number(Object x) {
        if (x instanceof Number n) {
            return n;
        }
        if (x == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(x));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double num(Object x) {
        return number(x).doubleValue();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(
                s == null ? "" : s,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String strip(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
