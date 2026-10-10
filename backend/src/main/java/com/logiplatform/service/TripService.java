package com.logiplatform.service;

import com.logiplatform.dto.ShipmentDtos;
import com.logiplatform.model.Driver;
import com.logiplatform.model.DriverStatus;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.Trip;
import com.logiplatform.model.TripStatus;
import com.logiplatform.model.DispatchStop;
import com.logiplatform.model.Vehicle;
import com.logiplatform.model.VehicleStatus;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.repository.TripShipmentRepository;
import com.logiplatform.repository.ShipmentTrackingEventRepository;
import com.logiplatform.model.TrackingEventType;
import com.logiplatform.model.ShipmentTrackingEvent;
import com.logiplatform.repository.DispatchStopRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.logiplatform.dto.TmsDtos.*;

/**
 * Transactional dispatch workflow. Vehicle/driver rows are locked at transition
 * time so two concurrent requests cannot start different trips with the same
 * physical resources. Shipment delivery is only confirmed by proof of delivery.
 */
@Service
public class TripService {
    private final TripRepository tripRepository;
    private final TripShipmentRepository tripShipmentRepository;
    private final DispatchStopRepository dispatchStops;
    private final ShipmentTrackingEventRepository trackingEvents;
    private final VehicleService vehicleService;
    private final DriverService driverService;
    private final ShipmentService shipmentService;

    public TripService(TripRepository tripRepository,
                       TripShipmentRepository tripShipmentRepository,
                       DispatchStopRepository dispatchStops,
                       ShipmentTrackingEventRepository trackingEvents,
                       VehicleService vehicleService,
                       DriverService driverService,
                       ShipmentService shipmentService) {
        this.tripRepository = tripRepository;
        this.tripShipmentRepository = tripShipmentRepository;
        this.dispatchStops = dispatchStops;
        this.trackingEvents = trackingEvents;
        this.vehicleService = vehicleService;
        this.driverService = driverService;
        this.shipmentService = shipmentService;
    }

    @Transactional
    public TripResponse create(CreateTripRequest request) {
        UUID tenantId = requireTenant();
        // Lock resources while validating so concurrent trip planning/start requests serialize.
        Vehicle vehicle = vehicleService.getOwnedForUpdate(request.vehicleId());
        Driver driver = driverService.getOwnedForUpdate(request.driverId());
        requireAvailableResources(vehicle, driver);

        List<UUID> shipmentIds = request.shipmentIds() == null ? List.of() : new java.util.ArrayList<>(request.shipmentIds());
        if (shipmentIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment assignment cannot contain null IDs");
        }
        if (request.originAddress() == null || request.originAddress().isBlank()
                || request.destinationAddress() == null || request.destinationAddress().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trip origin and destination are required");
        }
        if (sameLocation(request.originAddress(), request.destinationAddress())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trip origin and destination must differ");
        }
        if (new HashSet<>(shipmentIds).size() != shipmentIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A shipment can appear only once in a trip assignment");
        }

        BigDecimal totalCargoWeight = BigDecimal.ZERO;
        // Acquire shipment locks in a deterministic order to avoid deadlocks when
        // concurrent trip requests contain overlapping shipment sets.
        for (UUID shipmentId : shipmentIds.stream().sorted().toList()) {
            Shipment shipment = shipmentService.getOwnedForUpdate(shipmentId);
            if (tripShipmentRepository.hasPlannedOrActiveTripAssignment(tenantId, shipmentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Shipment " + shipmentId + " is already assigned to a planned or active trip");
            }
            ShipmentStatus shipmentStatus = shipment.getStatus();
            if (shipmentStatus == ShipmentStatus.ARRIVED) {
                ShipmentTrackingEvent latestArrival = trackingEvents
                        .findFirstByTenantIdAndShipmentIdAndEventTypeInOrderByOccurredAtDesc(
                                tenantId, shipmentId, List.of(TrackingEventType.ARRIVED_AT_HUB,
                                        TrackingEventType.ARRIVED_DESTINATION))
                        .orElse(null);
                if (latestArrival != null && latestArrival.getLocation() != null
                        && !sameLocation(latestArrival.getLocation(), request.originAddress())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Shipment " + shipment.getReferenceCode() + " last arrived at "
                                    + latestArrival.getLocation() + "; the next trip must originate from that location");
                }
            }
            boolean dispatchable = shipmentStatus == ShipmentStatus.BOOKED
                    || shipmentStatus == ShipmentStatus.PICKED_UP
                    || shipmentStatus == ShipmentStatus.DEPARTED
                    || shipmentStatus == ShipmentStatus.ARRIVED;
            if (!dispatchable) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Shipment " + shipment.getReferenceCode()
                                + " must be BOOKED, PICKED_UP, DEPARTED or ARRIVED before trip assignment (current: "
                                + shipmentStatus + ")");
            }

            BigDecimal weight = declaredWeight(shipment.getWeightKg(), shipment.getGrossWeightKg());
            if (weight == null || weight.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Shipment " + shipment.getReferenceCode() + " needs a positive declared weight before dispatch");
            }
            totalCargoWeight = totalCargoWeight.add(weight);
        }

        BigDecimal capacity = BigDecimal.valueOf(vehicle.getCapacityKg());
        if (totalCargoWeight.compareTo(capacity) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Assigned cargo weight " + totalCargoWeight.stripTrailingZeros().toPlainString()
                            + " kg exceeds vehicle capacity " + vehicle.getCapacityKg() + " kg");
        }

        Trip trip = tripRepository.saveAndFlush(new Trip(tenantId, vehicle.getId(), driver.getId(),
                request.originAddress().trim(), request.destinationAddress().trim(), request.scheduledDeparture()));
        for (UUID shipmentId : shipmentIds) {
            tripShipmentRepository.save(new com.logiplatform.model.TripShipment(tenantId, trip.getId(), shipmentId));
        }
        return TripResponse.from(trip, shipmentIds);
    }

    @Transactional(readOnly = true)
    public Page<TripResponse> list(Pageable pageable) {
        UUID tenantId = requireTenant();
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
        Trip trip = findOwnedForUpdate(tripId);
        List<UUID> shipmentIds = shipmentIdsFor(tripId);

        // A dispatch cannot start with an unbooked, held, cancelled or already
        // delivered shipment. This preflight happens before any state is changed.
        for (UUID shipmentId : shipmentIds) {
            ShipmentDtos.ShipmentResponse shipment = shipmentService.get(shipmentId);
            ShipmentStatus status = ShipmentStatus.valueOf(shipment.status());
            if (status != ShipmentStatus.BOOKED
                    && status != ShipmentStatus.PICKED_UP
                    && status != ShipmentStatus.DEPARTED
                    && status != ShipmentStatus.ARRIVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Shipment " + shipment.referenceCode()
                                + " must be BOOKED, PICKED_UP, DEPARTED or ARRIVED before trip start (current: "
                                + status + ")");
            }
        }

        transition(trip::start);
        tripRepository.save(trip);

        Vehicle vehicle = vehicleService.getOwnedForUpdate(trip.getVehicleId());
        Driver driver = driverService.getOwnedForUpdate(trip.getDriverId());
        requireAvailableResources(vehicle, driver);
        transition(vehicle::markOnTrip);
        vehicleService.save(vehicle);
        transition(driver::markOnTrip);
        driverService.save(driver);

        for (UUID shipmentId : shipmentIds) {
            shipmentService.updateStatus(shipmentId, new ShipmentDtos.UpdateStatusRequest("IN_TRANSIT"));
        }
        return TripResponse.from(trip, shipmentIds);
    }

    @Transactional
    public TripResponse complete(UUID tripId) {
        Trip trip = findOwnedForUpdate(tripId);
        UUID tenantId = requireTenant();
        List<DispatchStop> routeStops = dispatchStops.findAllByTenantIdAndTripIdOrderBySequenceNoAsc(tenantId, tripId);
        boolean unfinishedStop = routeStops.stream().anyMatch(stop ->
                !"COMPLETED".equals(stop.getStatus()) && !"SKIPPED".equals(stop.getStatus()));
        if (unfinishedStop) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A trip cannot be completed until every dispatch stop is completed or explicitly skipped");
        }
        transition(trip::complete);
        tripRepository.save(trip);
        freeUpResources(trip);

        // Arrival at the trip destination is not delivery to the consignee. The
        // latter requires a separately captured successful proof of delivery.
        for (UUID shipmentId : shipmentIdsFor(tripId)) {
            ShipmentDtos.ShipmentResponse shipment = shipmentService.get(shipmentId);
            ShipmentStatus status = ShipmentStatus.valueOf(shipment.status());
            if (status == ShipmentStatus.IN_TRANSIT) {
                boolean finalDestination = sameLocation(trip.getDestinationAddress(), shipment.destinationAddress());
                shipmentService.updateStatusFromTrip(shipmentId,
                        new ShipmentDtos.UpdateStatusRequest("ARRIVED"),
                        trip.getDestinationAddress(), finalDestination);
            }
        }
        return TripResponse.from(trip, shipmentIdsFor(tripId));
    }

    @Transactional
    public TripResponse cancel(UUID tripId) {
        Trip trip = findOwnedForUpdate(tripId);
        boolean wasInProgress = trip.getStatus() == TripStatus.IN_PROGRESS;
        transition(trip::cancel);
        tripRepository.save(trip);

        if (wasInProgress) {
            freeUpResources(trip);
            // Cargo on a cancelled in-progress movement is not assumed to be at
            // its destination. Put it on hold until operations reconciles it.
            for (UUID shipmentId : shipmentIdsFor(tripId)) {
                ShipmentDtos.ShipmentResponse shipment = shipmentService.get(shipmentId);
                ShipmentStatus status = ShipmentStatus.valueOf(shipment.status());
                if (status != ShipmentStatus.DELIVERED
                        && status != ShipmentStatus.COMPLETED
                        && status != ShipmentStatus.CANCELLED
                        && status != ShipmentStatus.ON_HOLD) {
                    shipmentService.updateStatus(shipmentId, new ShipmentDtos.UpdateStatusRequest("ON_HOLD"));
                }
            }
        }
        return TripResponse.from(trip, shipmentIdsFor(tripId));
    }

    private void freeUpResources(Trip trip) {
        Vehicle vehicle = vehicleService.getOwnedForUpdate(trip.getVehicleId());
        if (vehicle.getStatus() == VehicleStatus.ON_TRIP) {
            vehicle.markAvailable();
            vehicleService.save(vehicle);
        }

        Driver driver = driverService.getOwnedForUpdate(trip.getDriverId());
        if (driver.getStatus() == DriverStatus.ON_TRIP) {
            driver.markAvailable();
            driverService.save(driver);
        }
    }

    private List<UUID> shipmentIdsFor(UUID tripId) {
        UUID tenantId = requireTenant();
        return tripShipmentRepository.findAllByTenantIdAndId_TripId(tenantId, tripId)
                .stream().map(ts -> ts.getId().getShipmentId()).toList();
    }

    private Trip findOwned(UUID tripId) {
        UUID tenantId = requireTenant();
        return tripRepository.findByIdAndTenantId(tripId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
    }

    private Trip findOwnedForUpdate(UUID tripId) {
        UUID tenantId = requireTenant();
        return tripRepository.findLockedByIdAndTenantId(tripId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found"));
    }

    private static boolean sameLocation(String first, String second) {
        if (first == null || second == null) return false;
        return first.trim().replaceAll("\\s+", " ")
                .equalsIgnoreCase(second.trim().replaceAll("\\s+", " "));
    }

    private static BigDecimal declaredWeight(Integer weightKg, BigDecimal grossWeightKg) {
        if (grossWeightKg != null && grossWeightKg.signum() > 0) return grossWeightKg;
        return weightKg == null ? null : BigDecimal.valueOf(weightKg);
    }

    private static void requireAvailableResources(Vehicle vehicle, Driver driver) {
        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Vehicle " + vehicle.getRegistrationNumber() + " is not AVAILABLE (status: " + vehicle.getStatus() + ")");
        }
        if (driver.getStatus() != DriverStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Driver " + driver.getFullName() + " is not AVAILABLE (status: " + driver.getStatus() + ")");
        }
    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }
        return tenantId;
    }

    /** Converts a state-machine violation into a conflict response. */
    private static void transition(Runnable stateChange) {
        try {
            stateChange.run();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
