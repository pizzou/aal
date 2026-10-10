package com.logiplatform.service;

import com.logiplatform.dto.OperationsDtos.PodRequest;
import com.logiplatform.dto.OperationsDtos.PodResponse;
import com.logiplatform.dto.ShipmentDtos.UpdateStatusRequest;
import com.logiplatform.model.ProofOfDelivery;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.Trip;
import com.logiplatform.model.TripStatus;
import com.logiplatform.model.TrackingEventType;
import com.logiplatform.model.ShipmentTrackingEvent;
import com.logiplatform.repository.DispatchStopRepository;
import com.logiplatform.repository.ShipmentTrackingEventRepository;
import com.logiplatform.repository.ProofOfDeliveryRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.repository.TripShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class ProofOfDeliveryService {
    private final ProofOfDeliveryRepository pods;
    private final ShipmentRepository shipments;
    private final TripRepository trips;
    private final TripShipmentRepository tripShipments;
    private final DispatchStopRepository dispatchStops;
    private final ShipmentTrackingEventRepository trackingEvents;
    private final ShipmentService shipmentService;

    public ProofOfDeliveryService(ProofOfDeliveryRepository pods,
                                  ShipmentRepository shipments,
                                  TripRepository trips,
                                  TripShipmentRepository tripShipments,
                                  DispatchStopRepository dispatchStops,
                                  ShipmentTrackingEventRepository trackingEvents,
                                  ShipmentService shipmentService) {
        this.pods = pods;
        this.shipments = shipments;
        this.trips = trips;
        this.tripShipments = tripShipments;
        this.dispatchStops = dispatchStops;
        this.trackingEvents = trackingEvents;
        this.shipmentService = shipmentService;
    }

    @Transactional
    public PodResponse create(PodRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        if (request == null || request.shipmentId() == null
                || request.recipientName() == null || request.recipientName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment and recipient name are required");
        }

        // Lock in a consistent order (trip, then shipment) with TripService.start/complete.
        Trip trip = null;
        if (request.tripId() != null) {
            trip = trips.findLockedByIdAndTenantId(request.tripId(), tenantId)
                    .orElseThrow(() -> notFound("Trip not found"));
            if (trip.getStatus() == TripStatus.PLANNED || trip.getStatus() == TripStatus.CANCELLED) {
                throw conflict("Delivery evidence cannot be recorded against a planned or cancelled trip");
            }
            if (!tripShipments.existsByTenantIdAndId_TripIdAndId_ShipmentId(
                    tenantId, trip.getId(), request.shipmentId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The shipment is not assigned to the supplied trip");
            }
        }

        Shipment shipment = shipments.findLockedByIdAndTenantId(request.shipmentId(), tenantId)
                .orElseThrow(() -> notFound("Shipment not found"));
        ShipmentStatus currentStatus = shipment.getStatus();
        if (currentStatus == ShipmentStatus.CANCELLED || currentStatus == ShipmentStatus.COMPLETED) {
            throw conflict("Proof of delivery cannot be recorded for a cancelled or completed shipment");
        }

        boolean success = request.failureReason() == null || request.failureReason().isBlank();
        if (success && pods.hasSuccessfulDeliveryEvidence(tenantId, shipment.getId())) {
            throw conflict("Successful proof of delivery already exists for this shipment");
        }

        boolean atFinalDestinationStop = trip != null && dispatchStops
                .existsByTenantIdAndTripIdAndShipmentIdAndAddressIgnoreCaseAndStatusIn(
                        tenantId, trip.getId(), shipment.getId(), shipment.getDestinationAddress(),
                        java.util.List.of("ARRIVED", "COMPLETED"));

        if (success) {
            boolean legacyAlreadyDelivered = currentStatus == ShipmentStatus.DELIVERED;
            boolean statusAllowsDelivery = currentStatus == ShipmentStatus.OUT_FOR_DELIVERY
                    || currentStatus == ShipmentStatus.ARRIVED
                    || currentStatus == ShipmentStatus.IN_TRANSIT
                    || legacyAlreadyDelivered;
            if (!statusAllowsDelivery) {
                throw conflict("Successful delivery requires final-destination arrival or OUT_FOR_DELIVERY status");
            }

            boolean completedTripAtDestination = trip != null
                    && trip.getStatus() == TripStatus.COMPLETED
                    && sameLocation(trip.getDestinationAddress(), shipment.getDestinationAddress());
            if (currentStatus == ShipmentStatus.IN_TRANSIT) {
                if (trip == null || (!atFinalDestinationStop && !completedTripAtDestination)) {
                    throw conflict("An IN_TRANSIT shipment can only be delivered after its assigned trip reaches the final destination");
                }
            }
            // Even legacy shipments already marked DELIVERED must not accept POD
            // attached to an unrelated trip or a trip that never reached the final stop.
            if (trip != null) {
                if (!atFinalDestinationStop && !completedTripAtDestination) {
                    throw conflict("Successful proof of delivery must correspond to a reached final-destination stop");
                }
            } else if (currentStatus == ShipmentStatus.ARRIVED) {
                ShipmentTrackingEvent arrival = trackingEvents
                        .findFirstByTenantIdAndShipmentIdAndEventTypeInOrderByOccurredAtDesc(
                                tenantId, shipment.getId(), java.util.List.of(
                                        TrackingEventType.ARRIVED_AT_HUB,
                                        TrackingEventType.ARRIVED_DESTINATION))
                        .orElse(null);
                if (arrival != null && arrival.getEventType() == TrackingEventType.ARRIVED_AT_HUB) {
                    throw conflict("Shipment has reached an intermediate hub, not its final destination");
                }
            }
        } else if (currentStatus != ShipmentStatus.OUT_FOR_DELIVERY) {
            // A failed delivery attempt is a last-mile event, not a generic shipment
            // exception. Require the final-mile state so pickups/transit/customs cannot
            // be recorded as failed customer deliveries. A retry must explicitly resume
            // the held shipment back to OUT_FOR_DELIVERY first.
            throw conflict("A failed delivery attempt can only be recorded while the shipment is OUT_FOR_DELIVERY");
        }

        Instant now = Instant.now();
        Instant deliveredAt = request.deliveredAt() == null ? now : request.deliveredAt();
        if (deliveredAt.isAfter(now.plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delivery time cannot be more than five minutes in the future");
        }
        if (deliveredAt.isBefore(shipment.getCreatedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delivery time cannot precede shipment creation");
        }
        if (trip != null && trip.getActualDeparture() != null && deliveredAt.isBefore(trip.getActualDeparture())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Delivery evidence cannot predate the trip departure");
        }
        validateCoordinates(request.latitude(), request.longitude());

        String failureReason = success ? null : request.failureReason().trim();
        ProofOfDelivery pod;
        try {
            pod = pods.saveAndFlush(new ProofOfDelivery(
                    tenantId, shipment.getId(), request.tripId(), request.recipientName().trim(),
                    blankToNull(request.recipientPhone()), blankToNull(request.signatureUri()),
                    blankToNull(request.photoUri()), deliveredAt, request.latitude(), request.longitude(),
                    failureReason, blankToNull(request.notes())));
        } catch (DataIntegrityViolationException ex) {
            // Database partial uniqueness is the concurrency-safe guard for one successful POD.
            throw conflict("A successful proof of delivery already exists for this shipment");
        }

        if (success && currentStatus != ShipmentStatus.DELIVERED) {
            // Advance through each required operational state; never jump straight
            // from IN_TRANSIT/ARRIVED to DELIVERED or bypass the final-mile milestone.
            if (currentStatus == ShipmentStatus.IN_TRANSIT) {
                shipmentService.updateStatusFromTrip(shipment.getId(),
                        new UpdateStatusRequest("ARRIVED"), shipment.getDestinationAddress(), true);
                shipmentService.updateStatus(shipment.getId(), new UpdateStatusRequest("OUT_FOR_DELIVERY"));
            } else if (currentStatus == ShipmentStatus.ARRIVED) {
                shipmentService.updateStatus(shipment.getId(), new UpdateStatusRequest("OUT_FOR_DELIVERY"));
            }
            shipmentService.updateStatus(shipment.getId(), new UpdateStatusRequest("DELIVERED"));
        } else if (!success) {
            shipmentService.updateStatus(shipment.getId(), new UpdateStatusRequest("ON_HOLD"));
        }
        return PodResponse.from(pod);
    }

    @Transactional(readOnly = true)
    public PodResponse get(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        // Prefer successful evidence when present; otherwise show the latest failed attempt.
        return pods.findSuccessfulByTenantAndShipment(tenantId, shipmentId).stream()
                .findFirst()
                .or(() -> pods.findFirstByTenantIdAndShipmentIdOrderByDeliveredAtDesc(tenantId, shipmentId))
                .map(PodResponse::from)
                .orElseThrow(() -> notFound("Proof of delivery not found"));
    }

    private static boolean sameLocation(String first, String second) {
        if (first == null || second == null) return false;
        return first.trim().replaceAll("\\s+", " ")
                .equalsIgnoreCase(second.trim().replaceAll("\\s+", " "));
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Latitude and longitude must be supplied together");
        }
        if (latitude == null) return;
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid proof-of-delivery coordinates");
        }
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
}
