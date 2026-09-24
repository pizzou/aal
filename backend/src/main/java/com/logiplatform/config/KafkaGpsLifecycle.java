package com.logiplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

@Component
public class KafkaGpsLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaGpsLifecycle.class);

    private final KafkaListenerEndpointRegistry registry;
    private final boolean kafkaEnabled;
    private final boolean gpsConsumerEnabled;
    private final String bootstrapServers;

    public KafkaGpsLifecycle(
            KafkaListenerEndpointRegistry registry,
            @Value("${aal.events.kafka-enabled:false}") boolean kafkaEnabled,
            @Value("${aal.events.gps-consumer-enabled:false}") boolean gpsConsumerEnabled,
            @Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers) {
        this.registry = registry;
        this.kafkaEnabled = kafkaEnabled;
        this.gpsConsumerEnabled = gpsConsumerEnabled;
        this.bootstrapServers = bootstrapServers == null ? "" : bootstrapServers.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startConfiguredConsumer() {
        MessageListenerContainer container = registry.getListenerContainer("aalGpsConsumer");
        if (container == null) {
            log.warn("GPS Kafka listener container was not registered");
            return;
        }
        if (!kafkaEnabled || !gpsConsumerEnabled) {
            log.info("GPS Kafka consumer is disabled; application will continue without Kafka");
            return;
        }
        if (bootstrapServers.isBlank()) {
            log.warn("GPS Kafka consumer is enabled but KAFKA_BOOTSTRAP_SERVERS is empty; leaving consumer stopped");
            return;
        }
        try {
            container.start();
            log.info("GPS Kafka consumer started for configured bootstrap servers");
        } catch (RuntimeException ex) {
            log.error("GPS Kafka consumer could not start; application remains available without Kafka", ex);
        }
    }
}
