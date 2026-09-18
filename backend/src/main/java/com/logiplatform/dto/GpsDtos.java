package com.logiplatform.dto;

import com.logiplatform.model.VehicleGpsPosition;


import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class GpsDtos {

    private GpsDtos() {}

    public record RecordPositionRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            Double speedKmh,
            Double headingDegrees,
            Instant recordedAt // if omitted, server defaults to now
    ) {}

    public record PositionResponse(
            UUID vehicleId, double latitude, double longitude,
            Double speedKmh, Double headingDegrees, Instant recordedAt
    ) {
        public static PositionResponse from(VehicleGpsPosition p) {
            return new PositionResponse(p.getVehicleId(), p.getLatitude(), p.getLongitude(),
                    p.getSpeedKmh(), p.getHeadingDegrees(), p.getRecordedAt());
        }
    }
}
