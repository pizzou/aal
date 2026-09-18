package com.logiplatform.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "shipment_sensor_thresholds")
public class SensorThreshold {

    @Id
    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "min_temperature_celsius")
    private Double minTemperatureCelsius;

    @Column(name = "max_temperature_celsius")
    private Double maxTemperatureCelsius;

    @Column(name = "min_humidity_percent")
    private Double minHumidityPercent;

    @Column(name = "max_humidity_percent")
    private Double maxHumidityPercent;

    protected SensorThreshold() {}

    public SensorThreshold(UUID shipmentId, UUID tenantId, Double minTemperatureCelsius,
                            Double maxTemperatureCelsius, Double minHumidityPercent, Double maxHumidityPercent) {
        this.shipmentId = shipmentId;
        this.tenantId = tenantId;
        this.minTemperatureCelsius = minTemperatureCelsius;
        this.maxTemperatureCelsius = maxTemperatureCelsius;
        this.minHumidityPercent = minHumidityPercent;
        this.maxHumidityPercent = maxHumidityPercent;
    }

    /** Returns a human-readable violation description, or null if the reading is within range. */
    public String checkViolation(Double temperatureCelsius, Double humidityPercent) {
        if (temperatureCelsius != null) {
            if (minTemperatureCelsius != null && temperatureCelsius < minTemperatureCelsius) {
                return "Temperature " + temperatureCelsius + "C below minimum " + minTemperatureCelsius + "C";
            }
            if (maxTemperatureCelsius != null && temperatureCelsius > maxTemperatureCelsius) {
                return "Temperature " + temperatureCelsius + "C above maximum " + maxTemperatureCelsius + "C";
            }
        }
        if (humidityPercent != null) {
            if (minHumidityPercent != null && humidityPercent < minHumidityPercent) {
                return "Humidity " + humidityPercent + "% below minimum " + minHumidityPercent + "%";
            }
            if (maxHumidityPercent != null && humidityPercent > maxHumidityPercent) {
                return "Humidity " + humidityPercent + "% above maximum " + maxHumidityPercent + "%";
            }
        }
        return null;
    }

    public UUID getShipmentId() { return shipmentId; }
    public Double getMinTemperatureCelsius() { return minTemperatureCelsius; }
    public Double getMaxTemperatureCelsius() { return maxTemperatureCelsius; }
    public Double getMinHumidityPercent() { return minHumidityPercent; }
    public Double getMaxHumidityPercent() { return maxHumidityPercent; }
}
