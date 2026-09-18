package com.logiplatform.dto;

import com.logiplatform.model.Driver;
import com.logiplatform.model.Trip;
import com.logiplatform.model.Vehicle;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TmsDtos {

    private TmsDtos() {}

    public record CreateVehicleRequest(
            @NotBlank String registrationNumber,
            @NotNull String vehicleType,
            @Positive int capacityKg
    ) {}

    public record VehicleResponse(
            UUID id, String registrationNumber, String vehicleType, int capacityKg,
            String status, Instant createdAt
    ) {
        public static VehicleResponse from(Vehicle v) {
            return new VehicleResponse(v.getId(), v.getRegistrationNumber(), v.getVehicleType().name(),
                    v.getCapacityKg(), v.getStatus().name(), v.getCreatedAt());
        }
    }

    public record CreateDriverRequest(
            @NotBlank String fullName,
            @NotBlank String licenseNumber,
            String phone
    ) {}

    public record DriverResponse(
            UUID id, String fullName, String licenseNumber, String phone, String status, Instant createdAt
    ) {
        public static DriverResponse from(Driver d) {
            return new DriverResponse(d.getId(), d.getFullName(), d.getLicenseNumber(),
                    d.getPhone(), d.getStatus().name(), d.getCreatedAt());
        }
    }

    public record CreateTripRequest(
            @NotNull UUID vehicleId,
            @NotNull UUID driverId,
            @NotBlank String originAddress,
            @NotBlank String destinationAddress,
            Instant scheduledDeparture,
            List<UUID> shipmentIds // optional — shipments to assign to this trip at creation
    ) {}

    public record TripResponse(
            UUID id, UUID vehicleId, UUID driverId, String originAddress, String destinationAddress,
            String status, Instant scheduledDeparture, Instant actualDeparture, Instant actualArrival,
            List<UUID> shipmentIds
    ) {
        public static TripResponse from(Trip t, List<UUID> shipmentIds) {
            return new TripResponse(t.getId(), t.getVehicleId(), t.getDriverId(), t.getOriginAddress(),
                    t.getDestinationAddress(), t.getStatus().name(), t.getScheduledDeparture(),
                    t.getActualDeparture(), t.getActualArrival(), shipmentIds);
        }
    }
}
