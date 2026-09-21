package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.logiplatform.dto.GpsDtos.GpsDeviceRequest;
import com.logiplatform.dto.GpsDtos.GpsDeviceResponse;
import com.logiplatform.dto.GpsDtos.PositionResponse;
import com.logiplatform.dto.GpsDtos.RecordPositionRequest;

import org.springframework.http.HttpStatus;

@Service
public class TraccarGpsService {
    private static final ParameterizedTypeReference<List<Map<String, Object>>> POSITIONS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final JdbcTemplate db;
    private final RestTemplate rest;
    private final GpsTrackingService gps;
    private final String baseUrl;
    private final String user;
    private final String password;

    public TraccarGpsService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            RestTemplate rest,
            GpsTrackingService gps,
            @Value("${traccar.api-base-url:${TRACCAR_API_BASE_URL:http://localhost:8082/api}}") String baseUrl,
            @Value("${traccar.username:${TRACCAR_USERNAME:}}") String user,
            @Value("${traccar.password:${TRACCAR_PASSWORD:}}") String password) {
        this.db = db;
        this.rest = rest;
        this.gps = gps;
        this.baseUrl = strip(baseUrl);
        this.user = user == null ? "" : user.trim();
        this.password = password == null ? "" : password;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !user.isBlank() && !password.isBlank();
    }

    public GpsDeviceResponse device(UUID vehicleId) {
        UUID tenantId = tenant();
        return db.query(
                        "SELECT id,vehicle_id,provider,external_device_id,enabled "
                                + "FROM vehicle_gps_devices "
                                + "WHERE tenant_id=? AND vehicle_id=? AND provider='TRACCAR'",
                        (rs, rowNum) -> new GpsDeviceResponse(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getBoolean(5)),
                        tenantId,
                        vehicleId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public GpsDeviceResponse saveDevice(UUID vehicleId, GpsDeviceRequest request) {
        UUID tenantId = tenant();
        vehicleIdOr404(vehicleId);
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO vehicle_gps_devices "
                        + "(id,tenant_id,vehicle_id,provider,external_device_id,enabled) "
                        + "VALUES(?,?,?,?,?,?) "
                        + "ON CONFLICT(tenant_id,vehicle_id,provider) DO UPDATE SET "
                        + "external_device_id=excluded.external_device_id, "
                        + "enabled=excluded.enabled,updated_at=now()",
                id,
                tenantId,
                vehicleId,
                "TRACCAR",
                request.externalDeviceId(),
                request.enabled());
        return device(vehicleId);
    }

    public PositionResponse sync(UUID vehicleId) {
        if (!configured()) {
            throw new IllegalStateException(
                    "Traccar is not configured. Set TRACCAR_API_BASE_URL, TRACCAR_USERNAME and TRACCAR_PASSWORD.");
        }

        GpsDeviceResponse device = device(vehicleId);
        if (device == null || !device.enabled()) {
            throw new IllegalStateException("No enabled Traccar device mapping exists for this vehicle");
        }

        String url = baseUrl + "/positions?deviceId="
                + URLEncoder.encode(device.externalDeviceId(), StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, loginCookie());

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                POSITIONS_TYPE);

        List<Map<String, Object>> positions = response.getBody();
        if (positions == null || positions.isEmpty()) {
            throw new IllegalStateException(
                    "Traccar returned no position for device " + device.externalDeviceId());
        }

        Map<String, Object> position = positions.get(0);
        double latitude = number(position.get("latitude"));
        double longitude = number(position.get("longitude"));
        Double speedKmh = position.get("speed") == null
                ? null
                : number(position.get("speed")) * 1.852;
        Double headingDegrees = position.get("course") == null
                ? null
                : number(position.get("course"));

        Object fixTime = position.get("fixTime");
        if (fixTime == null) {
            fixTime = position.get("deviceTime");
        }
        Instant recordedAt = parseInstant(fixTime);

        Double accuracy = position.get("accuracy") instanceof Number
                ? number(position.get("accuracy"))
                : null;

        return gps.recordPosition(
                vehicleId,
                new RecordPositionRequest(
                        latitude,
                        longitude,
                        speedKmh,
                        headingDegrees,
                        recordedAt),
                "TRACCAR",
                device.externalDeviceId(),
                accuracy,
                null);
    }

    private String loginCookie() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("email", user);
        form.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Void> response = rest.postForEntity(
                baseUrl + "/session",
                new HttpEntity<>(form, headers),
                Void.class);

        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            throw new IllegalStateException("Traccar authentication did not return a session cookie");
        }
        return cookies.get(0).split(";", 2)[0];
    }

    private void vehicleIdOr404(UUID id) {
        boolean exists = !db.query(
                "SELECT id FROM vehicles WHERE tenant_id=? AND id=?",
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                tenant(),
                id).isEmpty();
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found");
        }
    }

    private UUID tenant() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }
        return tenant;
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            throw new IllegalArgumentException("Expected a numeric Traccar value");
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static Instant parseInstant(Object value) {
        if (value == null) {
            return Instant.now();
        }
        try {
            return value instanceof Instant instant
                    ? instant
                    : Instant.parse(String.valueOf(value));
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    private static String strip(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }
}
