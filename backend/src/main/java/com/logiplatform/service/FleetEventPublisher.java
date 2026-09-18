package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class FleetEventPublisher {
    private final KafkaTemplate<String,Object> kafka;
    private final String topic;
    public FleetEventPublisher(KafkaTemplate<String,Object> kafka,@Value("${aal.events.gps-topic:aal.gps.position}") String topic){this.kafka=kafka;this.topic=topic;}
    public void publish(UUID tenantId, UUID vehicleId, double latitude, double longitude, Object recordedAt){
        kafka.send(topic, vehicleId.toString(), Map.of("tenantId",tenantId.toString(),"vehicleId",vehicleId.toString(),"latitude",latitude,"longitude",longitude,"recordedAt",String.valueOf(recordedAt)));
    }
}
