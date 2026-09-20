package com.logiplatform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationQueueMetrics {

    private final JdbcTemplate db;

    public NotificationQueueMetrics(
            @Qualifier("publicJdbcTemplate") JdbcTemplate db,
            MeterRegistry registry) {
        this.db = db;
        Gauge.builder("notification_queue_depth", this, NotificationQueueMetrics::depth)
                .description("Notifications waiting or retrying in the delivery queue")
                .register(registry);
        Gauge.builder("notification_queue_failed", this, NotificationQueueMetrics::failed)
                .description("Notifications currently marked FAILED and eligible for retry")
                .register(registry);
    }

    private double depth() {
        return count("status IN ('QUEUED','FAILED')");
    }

    private double failed() {
        return count("status='FAILED'");
    }

    private double count(String predicate) {
        try {
            Number value = db.queryForObject(
                    "SELECT COUNT(*) FROM notification_queue WHERE " + predicate,
                    Number.class);
            return value == null ? 0d : value.doubleValue();
        } catch (Exception ex) {
            return 0d;
        }
    }
}
