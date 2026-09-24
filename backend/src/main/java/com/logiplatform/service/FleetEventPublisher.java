package com.logiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FleetEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(FleetEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final boolean kafkaEnabled;
    private final String bootstrapServers;

    public FleetEventPublisher(
            @Qualifier("gpsKafkaTemplate") KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            @Value("${aal.events.gps-topic:aal.gps.position}") String topic,
            @Value("${aal.events.kafka-enabled:false}") boolean kafkaEnabled,
            @Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
        this.kafkaEnabled = kafkaEnabled;
        this.bootstrapServers = bootstrapServers == null ? "" : bootstrapServers.trim();
    }

    public void publish(UUID tenantId, UUID vehicleId, double latitude, double longitude, Object recordedAt) {
        if (!kafkaEnabled || bootstrapServers.isBlank()) {
            log.debug("GPS Kafka publishing is disabled or not configured; skipping event for vehicle {}", vehicleId);
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("tenantId", tenantId.toString());
            event.put("vehicleId", vehicleId.toString());
            event.put("latitude", latitude);
            event.put("longitude", longitude);
            event.put("recordedAt", recordedAt instanceof Instant i ? i.toString() : String.valueOf(recordedAt));
            event.put("source", "AAL_PLATFORM");
            kafka.send(topic, vehicleId.toString(), mapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to publish GPS event", e);
        }
    }
}
