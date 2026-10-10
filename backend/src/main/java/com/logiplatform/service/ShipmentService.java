package com.logiplatform.service;

import com.logiplatform.model.NotificationResponse;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.ShipmentTrackingEvent;
import com.logiplatform.repository.ShipmentTrackingEventRepository;
import com.logiplatform.repository.ProofOfDeliveryRepository;
import com.logiplatform.model.TrackingEventType;
import com.logiplatform.model.TransportMode;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Map;
import static com.logiplatform.dto.ShipmentDtos.*;
import static com.logiplatform.dto.CommandCenterShipmentDtos.UpdateRequest;

@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentTrackingEventRepository trackingEventRepository;
    private final NotificationService notificationService;
    private final FinancePostingService financePostingService;
    private final MilestoneOrchestrationService milestoneOrchestrationService;
    private final OperationsEventStreamService operationsEventStreamService;
    private final FinanceDocumentSequenceService documentSequences;
    private final BillingService billingService;
    private final ShipmentCreationIdempotencyService creationIdempotency;
    private final ProofOfDeliveryRepository proofOfDeliveryRepository;

    public ShipmentService(ShipmentRepository shipmentRepository,
            ShipmentTrackingEventRepository trackingEventRepository,
            NotificationService notificationService,
            FinancePostingService financePostingService,
            MilestoneOrchestrationService milestoneOrchestrationService,
            OperationsEventStreamService operationsEventStreamService,
            FinanceDocumentSequenceService documentSequences,
            BillingService billingService,
            ShipmentCreationIdempotencyService creationIdempotency,
            ProofOfDeliveryRepository proofOfDeliveryRepository) {
        this.shipmentRepository = shipmentRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.notificationService = notificationService;
        this.financePostingService = financePostingService;
        this.milestoneOrchestrationService = milestoneOrchestrationService;
        this.operationsEventStreamService = operationsEventStreamService;
        this.documentSequences = documentSequences;
        this.billingService = billingService;
        this.creationIdempotency = creationIdempotency;
        this.proofOfDeliveryRepository = proofOfDeliveryRepository;
    }

    @Transactional
    public ShipmentResponse create(CreateShipmentRequest request) {
        return create(request, null);
    }

    @Transactional
    public ShipmentResponse create(CreateShipmentRequest request, String idempotencyKey) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }

        TransportMode mode = parseTransportMode(request.transportMode());
        String requestHash = creationIdempotency.requestHash(request);
        String normalizedIdempotencyKey = creationIdempotency.normalize(idempotencyKey);

        if (normalizedIdempotencyKey != null) {
            ShipmentResponse prior = creationIdempotency.findExisting(normalizedIdempotencyKey, requestHash);
            if (prior != null) {
                return prior;
            }
            creationIdempotency.reserve(normalizedIdempotencyKey, requestHash);
        }

        // The shipment reference is an AAL operational identifier, not an AWB.
        // A real MAWB/HAWB belongs to AwbRecord and must come from an allocated
        // airline/agent AWB range or a configured carrier integration.
        String reference = request.referenceCode() == null || request.referenceCode().isBlank()
                ? nextShipmentReference()
                : request.referenceCode().trim();

        if (shipmentRepository.existsByTenantIdAndReferenceCode(tenantId, reference)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A shipment with reference code '" + reference + "' already exists");
        }

        String invoiceNumber = documentSequences.nextInvoiceNumber();

        Shipment shipment = new Shipment(
                tenantId, reference, request.originAddress(), request.destinationAddress(),
                mode, request.carrierName(), request.carrierReferenceNumber());
        shipment.updateCommandCenterFields(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "Outstanding", null, invoiceNumber, null, null, null, null, null, "USD");
        // Flush the JPA INSERT before the JDBC idempotency row references the shipment.
        // tenantJdbcTemplate uses a separate JDBC connection, so an unflushed JPA
        // INSERT is not yet visible to PostgreSQL's foreign-key check.
        Shipment saved = shipmentRepository.saveAndFlush(shipment);

        if (normalizedIdempotencyKey != null) {
            creationIdempotency.complete(normalizedIdempotencyKey, requestHash, saved.getId());
        }

        // Every shipment's timeline starts here — this is what "high-end tracking" is
        // built out of: a consistent audit trail from booking to delivery.
        trackingEventRepository.save(new ShipmentTrackingEvent(
                tenantId, saved.getId(), TrackingEventType.CREATED, request.originAddress(),
                "Shipment record created; booking confirmation is pending", Instant.now()));
        milestoneOrchestrationService.initialize(saved.getId(), mode.name(), request.originAddress(), request.destinationAddress());

        return ShipmentResponse.from(saved);
    }

    /**
     * Backward-compatible shipment listing used by service-level callers and tests
     * that only need pagination without filters.
     *
     * Keeping this overload preserves the original service API while the HTTP
     * endpoint can use the richer filtered listing method below.
     */
    @Transactional(readOnly = true)
    public Page<ShipmentResponse> list(Pageable pageable) {
        return list(pageable, null, null, null);
    }

    @Transactional(readOnly = true)
    public Page<ShipmentResponse> list(Pageable pageable, String query, String mode, String status) {
        UUID tenantId = TenantContext.getTenantId();

        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedMode = normalizeOptionalTransportMode(mode);
        String normalizedStatus = normalizeOptionalShipmentStatus(status);

        return shipmentRepository
                .search(tenantId, normalizedQuery, normalizedMode, normalizedStatus, pageable)
                .map(ShipmentResponse::from);
    }

    private String normalizeOptionalTransportMode(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return parseTransportMode(raw).name();
    }

    private String normalizeOptionalShipmentStatus(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            return ShipmentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid status: " + raw);
        }
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(UUID shipmentId) {
        Shipment shipment = findOwned(shipmentId);
        return ShipmentResponse.from(shipment);
    }

    @Transactional
    public ShipmentResponse updateStatus(UUID shipmentId, UpdateStatusRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment status is required");
        }
        Shipment shipment = findOwnedForUpdate(shipmentId);
        ShipmentStatus newStatus = parseShipmentStatus(request.status());
        Shipment saved = applyStatusTransition(shipment, newStatus);
        return ShipmentResponse.from(saved);
    }

    /** Applies an arrival received from dispatch at the leg destination. An intermediate arrival
     * is tracked as a hub arrival and does not complete the shipment's final-destination milestone. */
    @Transactional
    public ShipmentResponse updateStatusFromTrip(UUID shipmentId, UpdateStatusRequest request,
                                                 String physicalLocation, boolean finalDestinationArrival) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment status is required");
        }
        if (physicalLocation == null || physicalLocation.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trip arrival location is required");
        }
        Shipment shipment = findOwnedForUpdate(shipmentId);
        ShipmentStatus newStatus = parseShipmentStatus(request.status());
        if (newStatus != ShipmentStatus.ARRIVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trip arrival can only set shipment status to ARRIVED");
        }
        return ShipmentResponse.from(applyStatusTransition(
                shipment, newStatus, physicalLocation.trim(), finalDestinationArrival));
    }

    @Transactional
    public ShipmentResponse updateCommandCenter(UUID shipmentId, UpdateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }

        Shipment shipment = findOwnedForUpdate(shipmentId);
        ShipmentStatus requestedStatus = request.shipmentStatus() == null || request.shipmentStatus().isBlank()
                ? null : parseShipmentStatus(request.shipmentStatus());
        BigDecimal previousSupplierPaid = nz(shipment.getAmountPaidToSupply());
        BigDecimal requestedSupplierPaid = nz(request.amountPaidToSupply());
        if (previousSupplierPaid.signum() > 0 && request.currency() != null && shipment.getCurrency() != null
                && !shipment.getCurrency().equalsIgnoreCase(request.currency().trim())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Currency cannot be changed after supplier payments have been recorded");
        }
        if (requestedSupplierPaid.compareTo(previousSupplierPaid) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount paid to supply cannot be reduced after it has been recorded");
        }

        shipment.updateCommandCenterFields(
                request.clientName(), request.contact(), request.commodity(),
                request.originCountry(), request.originCityPort(),
                request.destinationCountry(), request.destinationCityPort(),
                request.grossWeightKg(), request.volumetricWeightKg(), request.packages(),
                request.airlineUsed(), request.serviceType(), request.operatorName(),
                request.supplierCost(), request.otherCost(), request.clientRevenue(),
                request.amountPaidByClient(), request.amountPaidToSupply(), request.otherExpenses(),
                request.paymentStatus(), request.ownerName(), request.invoiceNo(),
                request.etd(), request.eta(), request.nextAction(), request.nextActionDate(),
                request.notes(), request.currency());

        if (request.dateOpened() != null) {
            shipment.setDateOpened(request.dateOpened());
        }
        // Do not use the lenient workbook-import setter for interactive updates:
        // user-driven state changes must pass the canonical lifecycle, evidence,
        // timeline and milestone checks.
        if (requestedStatus != null && requestedStatus != shipment.getStatus()) {
            shipment = applyStatusTransition(shipment, requestedStatus);
        }
        Shipment saved = shipmentRepository.save(shipment);

       
        if (nz(saved.getAmountBilledToClient()).signum() > 0) {
            billingService.billShipment(saved.getId(),
                    request.nextActionDate() != null ? request.nextActionDate() : java.time.LocalDate.now().plusDays(30),
                    request.ownerName());
        }

        BigDecimal supplierDelta = requestedSupplierPaid.subtract(previousSupplierPaid);
        if (supplierDelta.signum() > 0) {
            financePostingService.postSupplierPayment(
                    tenantId, saved.getId(), requestedSupplierPaid, saved.getCurrency(), saved.getReferenceCode());
        }
        return ShipmentResponse.from(saved);
    }

    @Transactional
    public TrackingEventResponse addTrackingEvent(UUID shipmentId, AddTrackingEventRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        Shipment shipment = findOwned(shipmentId); // 404s if this shipment doesn't belong to the caller's tenant
        if (request == null || request.eventType() == null || request.eventType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tracking event type is required");
        }

        TrackingEventType type;
        try {
            type = TrackingEventType.valueOf(request.eventType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid eventType: " + request.eventType());
        }

        if (type != TrackingEventType.EXCEPTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "State-linked tracking events are generated by the shipment workflow; record an EXCEPTION or change shipment status instead");
        }
        Instant now = Instant.now();
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : now;
        validateTrackingTime(shipment, occurredAt, now);
        ShipmentTrackingEvent event = trackingEventRepository.save(new ShipmentTrackingEvent(
                tenantId, shipmentId, type, request.location(), request.notes(), occurredAt));

        if (type == TrackingEventType.EXCEPTION) {
            notificationService.notify(shipmentId, shipment.getNotificationEmail(),
                    "Attention needed: shipment " + shipment.getReferenceCode(),
                    "An exception was reported for shipment " + shipment.getReferenceCode() + ": "
                            + (request.notes() != null ? request.notes() : "no further details provided") + ".");
        }

        return TrackingEventResponse.from(event);
    }

    /**
     * Stores a time-stamped milestone directly observed from a carrier integration.
     * This is deliberately separate from user-authored events: provider observations
     * preserve the carrier's timestamp while state changes remain governed by the
     * canonical shipment state machine.
     */
    @Transactional
    public TrackingEventResponse recordExternalMilestone(UUID shipmentId, TrackingEventType type,
                                                          Instant occurredAt, String location, String notes) {
        if (type != TrackingEventType.DEPARTED_ORIGIN && type != TrackingEventType.ARRIVED_DESTINATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported external milestone type");
        }
        Shipment shipment = findOwnedForUpdate(shipmentId);
        Instant now = Instant.now();
        Instant observedAt = occurredAt == null ? now : occurredAt;
        validateTrackingTime(shipment, observedAt, now);
        ShipmentTrackingEvent event = trackingEventRepository.save(new ShipmentTrackingEvent(
                shipment.getTenantId(), shipment.getId(), type, location, notes, observedAt));
        return TrackingEventResponse.from(event);
    }

    private static void validateTrackingTime(Shipment shipment, Instant occurredAt, Instant now) {
        if (occurredAt.isAfter(now.plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tracking events cannot be recorded more than five minutes in the future");
        }
        if (shipment.getCreatedAt() != null && occurredAt.isBefore(shipment.getCreatedAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tracking events cannot predate shipment creation");
        }
    }

    @Transactional(readOnly = true)
    public List<TrackingEventResponse> trackingHistory(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        findOwned(shipmentId);
        return trackingEventRepository
                .findAllByTenantIdAndShipmentIdOrderByOccurredAtAsc(tenantId, shipmentId)
                .stream().map(TrackingEventResponse::from).toList();
    }

    @Transactional
    public ShipmentResponse updateWeight(UUID shipmentId, UpdateWeightRequest request) {
        Shipment shipment = findOwnedForUpdate(shipmentId);
        shipment.setWeightKg(request.weightKg());
        return ShipmentResponse.from(shipmentRepository.save(shipment));
    }

    @Transactional
    public ShipmentResponse updateNotificationEmail(UUID shipmentId, UpdateNotificationEmailRequest request) {
        Shipment shipment = findOwnedForUpdate(shipmentId);
        shipment.setNotificationEmail(request.notificationEmail());
        return ShipmentResponse.from(shipmentRepository.save(shipment));
    }

    @Transactional
    public ShipmentResponse updateFlightNumber(UUID shipmentId, UpdateFlightNumberRequest request) {
        Shipment shipment = findOwnedForUpdate(shipmentId);
        shipment.setFlightNumber(request.flightNumber());
        return ShipmentResponse.from(shipmentRepository.save(shipment));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> notificationHistory(UUID shipmentId, Pageable pageable) {
        findOwned(shipmentId); // enforce ownership before delegating
        return notificationService.history(shipmentId, pageable).map(NotificationResponse::from);
    }

    private ShipmentStatus parseShipmentStatus(String raw) {
        try {
            return ShipmentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + raw);
        }
    }

    /** Applies one canonical status transition and persists its side effects in the same transaction. */
    private Shipment applyStatusTransition(Shipment shipment, ShipmentStatus newStatus) {
        return applyStatusTransition(shipment, newStatus, null, true);
    }

    private Shipment applyStatusTransition(Shipment shipment, ShipmentStatus newStatus,
                                           String locationOverride, boolean finalDestinationArrival) {
        UUID tenantId = TenantContext.getTenantId();
        ShipmentStatus previousStatus = shipment.getStatus();
        if (previousStatus == newStatus) {
            return shipment;
        }
        if (newStatus == ShipmentStatus.DELIVERED
                && !proofOfDeliveryRepository.hasSuccessfulDeliveryEvidence(tenantId, shipment.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A successful proof of delivery must be recorded before marking a shipment DELIVERED");
        }
        try {
            shipment.updateStatus(newStatus);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }

        Shipment saved = shipmentRepository.save(shipment);
        Instant changedAt = Instant.now();
        TrackingEventType eventType = newStatus == ShipmentStatus.ARRIVED && !finalDestinationArrival
                ? TrackingEventType.ARRIVED_AT_HUB : trackingEventFor(newStatus);
        String location = locationOverride != null ? locationOverride : switch (newStatus) {
            case PENDING, PLANNING, BOOKED -> shipment.getOriginAddress();
            case ARRIVED, CUSTOMS, CUSTOMS_CLEARED, OUT_FOR_DELIVERY, DELIVERED, COMPLETED -> shipment.getDestinationAddress();
            default -> null;
        };
        String transitionNote = "Operational status changed from " + previousStatus + " to " + newStatus;
        if (newStatus == ShipmentStatus.ARRIVED && !finalDestinationArrival) {
            transitionNote += "; cargo has reached an intermediate trip destination, not final delivery";
        }
        trackingEventRepository.save(new ShipmentTrackingEvent(
                tenantId, shipment.getId(), eventType, location, transitionNote, changedAt));

        if (!(newStatus == ShipmentStatus.ARRIVED && !finalDestinationArrival)) {
            milestoneOrchestrationService.applyShipmentStatus(shipment.getId(), newStatus.name());
        }
        operationsEventStreamService.publish(tenantId, "shipment-status", Map.of(
                "shipmentId", shipment.getId(),
                "reference", shipment.getReferenceCode(),
                "previousStatus", previousStatus.name(),
                "status", newStatus.name(),
                "at", changedAt.toString()));

        if (newStatus == ShipmentStatus.DELIVERED) {
            notificationService.notify(shipment.getId(), shipment.getNotificationEmail(),
                    "Your shipment " + shipment.getReferenceCode() + " has been delivered",
                    "Proof of delivery has been recorded for shipment " + shipment.getReferenceCode()
                            + " (" + shipment.getOriginAddress() + " to " + shipment.getDestinationAddress() + ").");
        }
        return saved;
    }

    private static TrackingEventType trackingEventFor(ShipmentStatus status) {
        return switch (status) {
            case PENDING -> TrackingEventType.PENDING;
            case PLANNING -> TrackingEventType.PLANNING;
            case BOOKED -> TrackingEventType.BOOKED;
            case PICKED_UP -> TrackingEventType.PICKED_UP;
            case DEPARTED -> TrackingEventType.DEPARTED_ORIGIN;
            case IN_TRANSIT -> TrackingEventType.IN_TRANSIT;
            case ARRIVED -> TrackingEventType.ARRIVED_DESTINATION;
            case CUSTOMS -> TrackingEventType.CUSTOMS_HOLD;
            case CUSTOMS_CLEARED -> TrackingEventType.CUSTOMS_CLEARED;
            case OUT_FOR_DELIVERY -> TrackingEventType.OUT_FOR_DELIVERY;
            case DELIVERED -> TrackingEventType.DELIVERED;
            case COMPLETED -> TrackingEventType.COMPLETED;
            case ON_HOLD -> TrackingEventType.ON_HOLD;
            case CANCELLED -> TrackingEventType.CANCELLED;
        };
    }

    /** Loads and locks a shipment for atomic dispatch/POD/status workflows. */
    Shipment getOwnedForUpdate(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }
        return shipmentRepository.findLockedByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
    }

    private Shipment findOwned(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        return shipmentRepository.findByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String nextShipmentReference() {
        return documentSequences.nextShipmentNumber();
    }

    private TransportMode parseTransportMode(String raw) {
        try {
            return TransportMode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid transportMode: " + raw
                            + " (expected ROAD, AIR, SEA, RAIL, INLAND_WATERWAY, COURIER, LAST_MILE, RORO, or PROJECT_CARGO)");
        }
    }
}
