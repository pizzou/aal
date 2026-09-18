package com.logiplatform.service;

import com.logiplatform.dto.OperationsDtos.FleetLiveResponse;
import com.logiplatform.dto.OperationsDtos.RoutePlanResponse;
import com.logiplatform.dto.OperationsDtos.StopRequest;
import com.logiplatform.dto.OperationsDtos.StopResponse;
import com.logiplatform.model.DispatchStop;
import com.logiplatform.repository.DispatchStopRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.tenancy.TenantContext;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DispatchOperationsService {

    private final DispatchStopRepository stops;
    private final TripRepository trips;
    private final ShipmentRepository shipments;
    private final NotificationService notifications;

    public DispatchOperationsService(
            DispatchStopRepository stops,
            TripRepository trips,
            ShipmentRepository shipments,
            NotificationService notifications) {
        this.stops = stops;
        this.trips = trips;
        this.shipments = shipments;
        this.notifications = notifications;
    }

    @Transactional
    public StopResponse addStop(StopRequest request) {

        UUID tenantId = TenantContext.getTenantId();

        if (!trips.findByIdAndTenantId(request.tripId(), tenantId).isPresent()) {
            throw notFound("Trip not found");
        }

        if (stops.existsByTenantIdAndTripIdAndSequenceNo(
                tenantId,
                request.tripId(),
                request.sequenceNo())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Stop sequence already exists");
        }

        if (request.shipmentId() != null
                && !shipments.findByIdAndTenantId(
                        request.shipmentId(),
                        tenantId).isPresent()) {

            throw notFound("Shipment not found");
        }

        String stopType = request.stopType()
                .trim()
                .toUpperCase();

        DispatchStop stop = new DispatchStop(
                tenantId,
                request.tripId(),
                request.shipmentId(),
                request.sequenceNo(),
                stopType,
                request.address().trim(),
                request.latitude(),
                request.longitude(),
                request.plannedAt(),
                request.eta(),
                request.distanceFromPreviousKm(),
                request.plannedDurationMinutes(),
                request.notes());

        return StopResponse.from(stops.save(stop));
    }

    @Transactional(readOnly = true)
    public List<StopResponse> listStops(UUID tripId) {

        UUID tenantId = TenantContext.getTenantId();

        if (!trips.findByIdAndTenantId(tripId, tenantId).isPresent()) {
            throw notFound("Trip not found");
        }

        return stops
                .findAllByTenantIdAndTripIdOrderBySequenceNoAsc(
                        tenantId,
                        tripId)
                .stream()
                .map(StopResponse::from)
                .toList();
    }

    @Transactional
    public StopResponse arrive(UUID id) {

        DispatchStop stop = owned(id);

        stop.arrive(Instant.now());

        return StopResponse.from(stops.save(stop));
    }

    @Transactional
    public StopResponse depart(UUID id) {

        DispatchStop stop = owned(id);

        stop.depart(Instant.now());

        return StopResponse.from(stops.save(stop));
    }

    @Transactional(readOnly = true)
    public RoutePlanResponse plan(UUID tripId) {

        UUID tenantId = TenantContext.getTenantId();

        if (!trips.findByIdAndTenantId(tripId, tenantId).isPresent()) {
            throw notFound("Trip not found");
        }

        List<DispatchStop> routeStops = stops.findAllByTenantIdAndTripIdOrderBySequenceNoAsc(
                tenantId,
                tripId);

        double totalDistanceKm = 0.0;
        int totalDurationMinutes = 0;

        DispatchStop previous = null;

        for (DispatchStop stop : routeStops) {

            if (stop.getDistanceFromPreviousKm() != null) {

                /*
                 * Persistence remains BigDecimal.
                 * The route response contract is intentionally double,
                 * so conversion occurs only at this presentation boundary.
                 */
                totalDistanceKm += stop.getDistanceFromPreviousKm().doubleValue();

            } else if (previous != null
                    && previous.getLatitude() != null
                    && previous.getLongitude() != null
                    && stop.getLatitude() != null
                    && stop.getLongitude() != null) {

                totalDistanceKm += haversine(
                        previous.getLatitude(),
                        previous.getLongitude(),
                        stop.getLatitude(),
                        stop.getLongitude());
            }

            if (stop.getPlannedDurationMinutes() != null) {
                totalDurationMinutes += stop.getPlannedDurationMinutes();
            }

            previous = stop;
        }

        double roundedDistance = Math.round(totalDistanceKm * 1000.0) / 1000.0;

        return new RoutePlanResponse(
                tripId,
                roundedDistance,
                totalDurationMinutes,
                routeStops.stream()
                        .map(StopResponse::from)
                        .toList());
    }

    private DispatchStop owned(UUID id) {

        return stops
                .findByIdAndTenantId(
                        id,
                        TenantContext.getTenantId())
                .orElseThrow(
                        () -> notFound("Stop not found"));
    }

    private static ResponseStatusException notFound(String message) {

        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                message);
    }

    private static double haversine(
            double latitude1,
            double longitude1,
            double latitude2,
            double longitude2) {

        final double earthRadiusKm = 6371.0;

        double latitudeDelta = Math.toRadians(latitude2 - latitude1);

        double longitudeDelta = Math.toRadians(longitude2 - longitude1);

        double a = Math.sin(latitudeDelta / 2)
                * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(latitude1))
                        * Math.cos(Math.toRadians(latitude2))
                        * Math.sin(longitudeDelta / 2)
                        * Math.sin(longitudeDelta / 2);

        return 2.0
                * earthRadiusKm
                * Math.asin(Math.sqrt(a));
    }
}
