package com.logiplatform.service;

import com.logiplatform.dto.OperationsDtos.RoutePlanResponse;
import com.logiplatform.dto.OperationsDtos.StopRequest;
import com.logiplatform.dto.OperationsDtos.StopResponse;
import com.logiplatform.model.DispatchStop;
import com.logiplatform.model.Trip;
import com.logiplatform.model.TripStatus;
import com.logiplatform.repository.DispatchStopRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.repository.TripShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DispatchOperationsService {
    private final DispatchStopRepository stops;
    private final TripRepository trips;
    private final TripShipmentRepository tripShipments;
    private final ShipmentRepository shipments;

    public DispatchOperationsService(DispatchStopRepository stops,
                                     TripRepository trips,
                                     TripShipmentRepository tripShipments,
                                     ShipmentRepository shipments) {
        this.stops = stops;
        this.trips = trips;
        this.tripShipments = tripShipments;
        this.shipments = shipments;
    }

    @Transactional
    public StopResponse addStop(StopRequest request) {
        UUID tenantId = requireTenant();
        Trip trip = findTripForUpdate(request.tripId(), tenantId);
        if (trip.getStatus() != TripStatus.PLANNED) {
            throw conflict("Stops can only be changed while the trip is PLANNED");
        }
        if (stops.existsByTenantIdAndTripIdAndSequenceNo(tenantId, request.tripId(), request.sequenceNo())) {
            throw conflict("Stop sequence already exists");
        }

        validateCoordinates(request.latitude(), request.longitude());
        if (request.distanceFromPreviousKm() != null && request.distanceFromPreviousKm().signum() < 0) {
            throw badRequest("Distance from previous stop cannot be negative");
        }
        if (request.plannedDurationMinutes() != null && request.plannedDurationMinutes() < 0) {
            throw badRequest("Planned duration cannot be negative");
        }
        if (request.plannedAt() != null && request.eta() != null && request.eta().isBefore(request.plannedAt())) {
            throw badRequest("ETA cannot be earlier than the planned stop time");
        }
        String address = request.address().trim();
        if (address.length() > 500) throw badRequest("Stop address exceeds 500 characters");
        String stopType = request.stopType().trim().toUpperCase(Locale.ROOT);
        if (!stopType.matches("[A-Z][A-Z0-9_]{0,39}")) {
            throw badRequest("Stop type must be a simple operational code (letters, numbers and underscores)");
        }

        if (request.shipmentId() != null) {
            if (shipments.findByIdAndTenantId(request.shipmentId(), tenantId).isEmpty()) {
                throw notFound("Shipment not found");
            }
            if (!tripShipments.existsByTenantIdAndId_TripIdAndId_ShipmentId(
                    tenantId, trip.getId(), request.shipmentId())) {
                throw badRequest("The stop shipment must already be assigned to this trip");
            }
        }

        DispatchStop stop = new DispatchStop(tenantId, trip.getId(), request.shipmentId(),
                request.sequenceNo(), stopType, address, request.latitude(), request.longitude(),
                request.plannedAt(), request.eta(), request.distanceFromPreviousKm(),
                request.plannedDurationMinutes(), blankToNull(request.notes()));
        try {
            return StopResponse.from(stops.saveAndFlush(stop));
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Stop sequence already exists");
        }
    }

    @Transactional(readOnly = true)
    public List<StopResponse> listStops(UUID tripId) {
        UUID tenantId = requireTenant();
        findTrip(tripId, tenantId);
        return stops.findAllByTenantIdAndTripIdOrderBySequenceNoAsc(tenantId, tripId)
                .stream().map(StopResponse::from).toList();
    }

    @Transactional
    public StopResponse arrive(UUID id) {
        UUID tenantId = requireTenant();
        DispatchStop observed = owned(id, tenantId);
        // Serialize route execution against start/cancel/complete and competing stop actions.
        Trip trip = findTripForUpdate(observed.getTripId(), tenantId);
        DispatchStop stop = stops.findLockedByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Stop not found"));
        requireTripInProgress(trip);
        requireEarlierStopsComplete(stop, tenantId);
        try {
            stop.arrive(Instant.now());
        } catch (IllegalStateException ex) {
            throw conflict(ex.getMessage());
        }
        return StopResponse.from(stops.save(stop));
    }

    @Transactional
    public StopResponse depart(UUID id) {
        UUID tenantId = requireTenant();
        DispatchStop observed = owned(id, tenantId);
        Trip trip = findTripForUpdate(observed.getTripId(), tenantId);
        DispatchStop stop = stops.findLockedByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Stop not found"));
        requireTripInProgress(trip);
        try {
            stop.depart(Instant.now());
        } catch (IllegalStateException ex) {
            throw conflict(ex.getMessage());
        }
        return StopResponse.from(stops.save(stop));
    }

    @Transactional
    public StopResponse skip(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw badRequest("A reason is required when skipping a dispatch stop");
        }
        if (reason.trim().length() > 1000) {
            throw badRequest("Stop skip reason exceeds 1000 characters");
        }
        UUID tenantId = requireTenant();
        DispatchStop observed = owned(id, tenantId);
        Trip trip = findTripForUpdate(observed.getTripId(), tenantId);
        DispatchStop stop = stops.findLockedByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Stop not found"));
        if (trip.getStatus() != TripStatus.PLANNED && trip.getStatus() != TripStatus.IN_PROGRESS) {
            throw conflict("A stop can only be skipped while its trip is PLANNED or IN_PROGRESS");
        }
        if (trip.getStatus() == TripStatus.IN_PROGRESS) {
            requireEarlierStopsComplete(stop, tenantId);
        }
        try {
            stop.skip(reason);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw conflict(ex.getMessage());
        }
        return StopResponse.from(stops.save(stop));
    }

    @Transactional(readOnly = true)
    public RoutePlanResponse plan(UUID tripId) {
        UUID tenantId = requireTenant();
        findTrip(tripId, tenantId);
        List<DispatchStop> routeStops = stops.findAllByTenantIdAndTripIdOrderBySequenceNoAsc(tenantId, tripId);

        double totalDistanceKm = 0.0;
        long totalDurationMinutes = 0L;
        DispatchStop previous = null;
        for (DispatchStop stop : routeStops) {
            BigDecimal segment = stop.getDistanceFromPreviousKm();
            if (segment != null) {
                totalDistanceKm += segment.doubleValue();
            } else if (previous != null && hasCoordinates(previous) && hasCoordinates(stop)) {
                totalDistanceKm += haversine(previous.getLatitude(), previous.getLongitude(),
                        stop.getLatitude(), stop.getLongitude());
            }
            if (stop.getPlannedDurationMinutes() != null) {
                totalDurationMinutes += Math.max(0, stop.getPlannedDurationMinutes());
            }
            previous = stop;
        }
        if (!Double.isFinite(totalDistanceKm)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Route distance could not be calculated");
        }
        double roundedDistance = Math.round(totalDistanceKm * 1000.0) / 1000.0;
        if (totalDurationMinutes > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Route duration exceeds supported range");
        }
        return new RoutePlanResponse(tripId, roundedDistance, (int) totalDurationMinutes,
                routeStops.stream().map(StopResponse::from).toList());
    }

    private void requireEarlierStopsComplete(DispatchStop target, UUID tenantId) {
        for (DispatchStop preceding : stops.findAllByTenantIdAndTripIdOrderBySequenceNoAsc(tenantId, target.getTripId())) {
            if (preceding.getSequenceNo() >= target.getSequenceNo()) break;
            if (!"COMPLETED".equals(preceding.getStatus()) && !"SKIPPED".equals(preceding.getStatus())) {
                throw conflict("Complete or skip stop " + preceding.getSequenceNo() + " before arriving at stop " + target.getSequenceNo());
            }
        }
    }

    private void requireTripInProgress(Trip trip) {
        if (trip.getStatus() != TripStatus.IN_PROGRESS) {
            throw conflict("Stop execution requires a trip in IN_PROGRESS state");
        }
    }

    private DispatchStop owned(UUID id, UUID tenantId) {
        return stops.findByIdAndTenantId(id, tenantId).orElseThrow(() -> notFound("Stop not found"));
    }

    private Trip findTrip(UUID id, UUID tenantId) {
        return trips.findByIdAndTenantId(id, tenantId).orElseThrow(() -> notFound("Trip not found"));
    }

    private Trip findTripForUpdate(UUID id, UUID tenantId) {
        return trips.findLockedByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Trip not found"));
    }

    private static UUID requireTenant() {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        return tenantId;
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw badRequest("Latitude and longitude must be supplied together");
        }
        if (latitude == null) return;
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            throw badRequest("Invalid stop coordinates");
        }
    }

    private static boolean hasCoordinates(DispatchStop stop) {
        return stop.getLatitude() != null && stop.getLongitude() != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static double haversine(double latitude1, double longitude1, double latitude2, double longitude2) {
        final double earthRadiusKm = 6371.0;
        double latitudeDelta = Math.toRadians(latitude2 - latitude1);
        double longitudeDelta = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 2.0 * earthRadiusKm * Math.asin(Math.sqrt(Math.min(1.0, a)));
    }
}
