package com.logiplatform.service;

import com.logiplatform.dto.OperationsDtos.FleetLiveResponse;
import com.logiplatform.model.Driver;
import com.logiplatform.model.Trip;
import com.logiplatform.model.TripStatus;
import com.logiplatform.model.Vehicle;
import com.logiplatform.repository.DriverRepository;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.repository.VehicleGpsPositionRepository;
import com.logiplatform.repository.VehicleRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FleetControlService {

    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final TripRepository trips;
    private final VehicleGpsPositionRepository gps;

    public FleetControlService(
            VehicleRepository vehicles,
            DriverRepository drivers,
            TripRepository trips,
            VehicleGpsPositionRepository gps) {
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.trips = trips;
        this.gps = gps;
    }

    @Transactional(readOnly = true)
    public List<FleetLiveResponse> live() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return List.of();
        }

        List<Vehicle> vehicleList = vehicles.findAllByTenantId(tenantId);
        if (vehicleList.isEmpty()) {
            return List.of();
        }

        /*
         * The previous implementation queried the complete trip window once for
         * every vehicle and then queried the driver once per trip. On a real
         * fleet this becomes an N+1 query pattern and can turn a dashboard
         * refresh into a database bottleneck.
         */
        Instant now = Instant.now();
        List<Trip> activeTrips =
                trips.findAllByTenantIdAndScheduledDepartureGreaterThanEqualAndScheduledDepartureLessThanOrderByScheduledDepartureAsc(
                        tenantId,
                        now.minusSeconds(86400),
                        now.plusSeconds(86400 * 2L));

        Map<UUID, Trip> tripByVehicle = new HashMap<>();
        for (Trip trip : activeTrips) {
            if (trip == null
                    || trip.getVehicleId() == null
                    || (trip.getStatus() != TripStatus.IN_PROGRESS
                    && trip.getStatus() != TripStatus.PLANNED)) {
                continue;
            }
            // The repository is ordered by departure, so the first trip is the
            // nearest applicable trip for that vehicle.
            tripByVehicle.putIfAbsent(trip.getVehicleId(), trip);
        }

        Map<UUID, Driver> driverById = new HashMap<>();
        for (Driver driver : drivers.findAllByTenantId(tenantId)) {
            if (driver != null && driver.getId() != null) {
                driverById.put(driver.getId(), driver);
            }
        }

        List<FleetLiveResponse> out = new ArrayList<>(vehicleList.size());

        for (Vehicle vehicle : vehicleList) {
            UUID vehicleId = vehicle.getId();
            var position = gps
                    .findFirstByTenantIdAndVehicleIdOrderByRecordedAtDesc(tenantId, vehicleId)
                    .orElse(null);

            Trip trip = tripByVehicle.get(vehicleId);
            UUID driverId = trip == null ? null : trip.getDriverId();
            String driverName = driverId == null
                    ? null
                    : java.util.Optional.ofNullable(driverById.get(driverId))
                            .map(Driver::getFullName)
                            .orElse(null);

            String vehicleStatus = vehicle.getStatus() == null
                    ? "UNKNOWN"
                    : vehicle.getStatus().name();

            out.add(new FleetLiveResponse(
                    vehicleId,
                    vehicle.getRegistrationNumber(),
                    vehicleStatus,
                    driverId,
                    driverName,
                    trip == null ? null : trip.getId(),
                    trip == null || trip.getStatus() == null ? null : trip.getStatus().name(),
                    position == null ? null : position.getLatitude(),
                    position == null ? null : position.getLongitude(),
                    position == null ? null : position.getSpeedKmh(),
                    position == null ? null : position.getRecordedAt()));
        }

        return out;
    }
}
