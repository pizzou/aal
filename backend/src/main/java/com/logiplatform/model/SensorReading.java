package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_sensor_readings")
public class SensorReading {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "temperature_celsius", updatable = false)
    private Double temperatureCelsius;

    @Column(name = "humidity_percent", updatable = false)
    private Double humidityPercent;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SensorReading() {}

    public SensorReading(UUID tenantId, UUID shipmentId, Double temperatureCelsius,
                          Double humidityPercent, Instant recordedAt) {
        if (temperatureCelsius == null && humidityPercent == null) {
            throw new IllegalArgumentException("A reading must include at least temperature or humidity");
        }
        if (humidityPercent != null && (humidityPercent < 0 || humidityPercent > 100)) {
            throw new IllegalArgumentException("humidityPercent must be between 0 and 100, got " + humidityPercent);
        }
        this.tenantId = tenantId;
        this.shipmentId = shipmentId;
        this.temperatureCelsius = temperatureCelsius;
        this.humidityPercent = humidityPercent;
        this.recordedAt = recordedAt;
        this.eventId = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }
    public Double getTemperatureCelsius() { return temperatureCelsius; }
    public Double getHumidityPercent() { return humidityPercent; }
    public Instant getRecordedAt() { return recordedAt; }
}
