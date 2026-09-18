package com.logiplatform.service;

import com.logiplatform.model.Driver;
import com.logiplatform.model.DriverStatus;
import com.logiplatform.dto.ShipmentDtos;
import com.logiplatform.model.Trip;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.model.TripShipment;
import com.logiplatform.repository.TripShipmentRepository;
import com.logiplatform.model.Vehicle;
import com.logiplatform.model.VehicleStatus;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import static com.logiplatform.dto.TmsDtos.*;



/**
 * The core dispatch workflow. A trip's lifecycle drives real side effects elsewhere:
 * starting a trip marks its vehicle/driver ON_TRIP and its shipments IN_TRANSIT;
 * completing one frees the vehicle/driver and marks shipments DELIVERED; cancelling
 * frees the vehicle/driver without touching shipment status (a cancelled trip needs
 * its shipments re-assigned to a new trip, not silently marked as anything).
 */
@Service
public class TripService {

    private final TripRepository tripRepository;
    private final TripShipmentRepository tripShipmentRepository;
    private final VehicleService vehicleService;
    private final DriverService driverService;
    private final ShipmentService shipmentService;

    public TripService(TripRepository tripRepository, TripShipmentRepository tripShipmentRepository,
                        VehicleService vehicleService, DriverService driverService,
                        ShipmentService shipmentService) {
        this.tripRepository = tripRepository;
        this.tripShipmentRepository = tripShipmentRepository;
        this.vehicleService = vehicleService;
        this.driverService = driverService;
        this.shipmentService = shipmentService;
    }

    @Transactional
    public TripResponse create(CreateTripRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        Vehicle vehicle = vehicleService.getOwned(request.vehicleId());
        Driver driver = driverService.getOwned(request.driverId());

        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vehicle " + vehicle.getRegistrationNumber() + " is not AVAILABLE (status: " + vehicle.getStatus() + ")");
        }
        if (driver.getStatus() != DriverStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Driver " + driver.getFullName() + " is not AVAILABLE (status: " + driver.getStatus() + ")");
        }

        Trip trip = tripRepository.save(new Trip(tenantId, vehicle.getId(), driver.getId(),
                request.originAddress(), request.destinationAddress(), request.scheduledDeparture()));

        List<UUID> shipmentIds = request.shipmentIds() != null ? request.shipmentIds() : List.of();
        for (UUID shipmentId : shipmentIds) {
            shipmentService.get(shipmentId); // 404s if this shipment doesn't belong to the tenant
            tripShipmentRepository.save(new TripShipment(tenantId, trip.getId(), shipmentId));
        }

        return TripResponse.from(trip, shipmentIds);
    }

    @Transactional(readOnly = true)
    public Page<TripResponse> list(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return tripRepository.findAllByTenantId(tenantId, pageable)
                .map(trip -> TripResponse.from(trip, shipmentIdsFor(trip.getId())));
    }

    @Transactional(readOnly = true)
    public TripResponse get(UUID tripId) {
        Trip trip = findOwned(tripId);
        return TripResponse.from(trip, shipmentIdsFor(tripId));
    }

    @Transactional
    public TripResponse start(UUID tripId) {
        Trip trip = findOwned(tripId);
        transition(trip::start);
        tripRepository.save(trip);

        Vehicle vehicle = vehicleService.getOwned(trip.getVehicleId());
        transition(vehicle::markOnTrip);
        vehicleService.save(vehicle);

        Driver driver = driverService.getOwned(trip.getDriverId());
        transition(driver::markOnTrip);
        driverService.save(driver);

        for (UUID shipmentId : shipmentIdsFor(tripId)) {
            shipmentService.updateStatus(shipmentId, new ShipmentDtos.UpdateStatusRequest("IN_TRANSIT"));
        }

        return TripResponse.from(trip, shipmentIdsFor(tripId));
    }

    @Transactional
    public TripResponse complete(UUID tripId) {
        Trip trip = findOwned(tripId);
        transition(trip::complete);
        tripRepository.save(trip);

        freeUpResources(trip);

        for (UUID shipmentId : shipmentIdsFor(tripId)) {
            shipmentService.updateStatus(shipmentId, new ShipmentDtos.UpdateStatusRequest("DELIVERED"));
        }

        return TripResponse.from(trip, shipmentIdsFor(tripId));
    }

    @Transactional
    public TripResponse cancel(UUID tripId) {
        Trip trip = findOwned(tripId);
        transition(trip::cancel);
        tripRepository.save(trip);
        freeUpResources(trip);
        // Deliberately NOT touching shipment status here — see class-level comment.
        return TripResponse.from(trip, shipmentIdsFor(tripId));
    }

    /** Converts a Trip state-machine violation into a proper 409, same pattern as StockMovementService. */
    private void transition(Runnable stateChange) {
        try {
            stateChange.run();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private void freeUpResources(Trip trip) {
        Vehicle vehicle = vehicleService.getOwned(trip.getVehicleId());
        vehicle.markAvailable();
        vehicleService.save(vehicle);

        Driver driver = driverService.getOwned(trip.getDriverId());
        driver.markAvailable();
        driverService.save(driver);
    }

    private List<UUID> shipmentIdsFor(UUID tripId) {
        UUID tenantId = TenantContext.getTenantId();
        return tripShipmentRepository.findAllByTenantIdAndId_TripId(tenantId, tripId)
                .stream().map(ts -> ts.getId().getShipmentId()).toList();
    }

    private Trip findOwned(UUID tripId) {
        UUID tenantId = TenantContext.getTenantId();
        return tripRepository.findByIdAndTenantId(tripId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
    }
}

