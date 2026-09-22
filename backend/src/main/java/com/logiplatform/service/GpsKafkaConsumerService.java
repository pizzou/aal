package com.logiplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class GpsKafkaConsumerService {
    private final ObjectMapper mapper;
    private final JdbcTemplate db;
    private final GpsTrackingService gps;
    private final String topic;

    public GpsKafkaConsumerService(
            ObjectMapper mapper,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            GpsTrackingService gps,
            @Value("${aal.events.gps-topic:aal.gps.position}") String topic) {
        this.mapper = mapper;
        this.db = db;
        this.gps = gps;
        this.topic = topic;
    }

    @Transactional
    @KafkaListener(topics = "${aal.events.gps-topic:aal.gps.position}", containerFactory = "gpsKafkaListenerContainerFactory", autoStartup = "${aal.events.gps-consumer-enabled:false}")
    public void consume(String payload) {
        Map<String,Object> event;
        try {
            event = mapper.readValue(payload, new TypeReference<Map<String,Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid GPS Kafka event", e);
        }

        UUID tenantId = UUID.fromString(String.valueOf(required(event, "tenantId")));
        UUID vehicleId = UUID.fromString(String.valueOf(required(event, "vehicleId")));
        String eventId = String.valueOf(event.getOrDefault("eventId", UUID.randomUUID().toString()));
        double latitude = number(event.get("latitude"));
        double longitude = number(event.get("longitude"));
        Double speed = nullableNumber(event.get("speedKmh"));
        Double heading = nullableNumber(event.get("headingDegrees"));
        Double accuracy = nullableNumber(event.get("accuracyMeters"));
        Double battery = nullableNumber(event.get("batteryPercent"));
        Instant recordedAt = Instant.parse(String.valueOf(event.getOrDefault("recordedAt", Instant.now().toString())));
        String source = String.valueOf(event.getOrDefault("source", "KAFKA"));
        String deviceId = event.get("deviceId") == null ? null : String.valueOf(event.get("deviceId"));
        if ("AAL_PLATFORM".equalsIgnoreCase(source)) return;

        TenantContext.setTenantId(tenantId);
        try {
            int inserted = db.update("""
                    INSERT INTO gps_ingestion_events
                        (tenant_id,event_id,vehicle_id,device_id,source,latitude,longitude,speed_kmh,
                         heading_degrees,accuracy_meters,battery_percent,recorded_at,status)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'RECEIVED')
                    ON CONFLICT (tenant_id,event_id) DO NOTHING
                    """, tenantId,eventId,vehicleId,deviceId,source,latitude,longitude,speed,heading,accuracy,battery,recordedAt);

            if (inserted == 0) return;

            try {
                gps.recordIngestedPosition(vehicleId, latitude, longitude, speed, heading, recordedAt, source, deviceId, accuracy, battery);
                db.update("UPDATE gps_ingestion_events SET processed_at=now(),status='PROCESSED' WHERE tenant_id=? AND event_id=?", tenantId,eventId);
            } catch (Exception e) {
                db.update("UPDATE gps_ingestion_events SET processed_at=now(),status='FAILED',error_detail=? WHERE tenant_id=? AND event_id=?", truncate(e.getMessage()),tenantId,eventId);
                throw e;
            }
        } finally {
            TenantContext.clear();
        }
    }

    private static Object required(Map<String,Object> event, String key) {
        Object value = event.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("Missing GPS field: " + key);
        return value;
    }

    private static double number(Object value) { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); }
    private static Double nullableNumber(Object value) { return value == null ? null : number(value); }
    private static String truncate(String value) { return value == null ? "Unknown GPS processing failure" : value.substring(0, Math.min(2000, value.length())); }
}
