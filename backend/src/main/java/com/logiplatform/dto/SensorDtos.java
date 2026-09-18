package com.logiplatform.dto;

import com.logiplatform.model.SensorReading;
import com.logiplatform.model.SensorThreshold;


import java.time.Instant;
import java.util.UUID;

public final class SensorDtos {

    private SensorDtos() {}

    public record RecordReadingRequest(
            Double temperatureCelsius,
            Double humidityPercent,
            Instant recordedAt,
            UUID eventId
    ) {}

    public record ReadingResponse(
            UUID id, Double temperatureCelsius, Double humidityPercent, Instant recordedAt, boolean violatesThreshold
    ) {
        public static ReadingResponse from(SensorReading r, boolean violatesThreshold) {
            return new ReadingResponse(r.getId(), r.getTemperatureCelsius(), r.getHumidityPercent(),
                    r.getRecordedAt(), violatesThreshold);
        }
    }

    public record SetThresholdRequest(
            Double minTemperatureCelsius,
            Double maxTemperatureCelsius,
            Double minHumidityPercent,
            Double maxHumidityPercent
    ) {}

    public record ThresholdResponse(
            Double minTemperatureCelsius, Double maxTemperatureCelsius,
            Double minHumidityPercent, Double maxHumidityPercent
    ) {
        public static ThresholdResponse from(SensorThreshold t) {
            return new ThresholdResponse(t.getMinTemperatureCelsius(), t.getMaxTemperatureCelsius(),
                    t.getMinHumidityPercent(), t.getMaxHumidityPercent());
        }
    }
}
