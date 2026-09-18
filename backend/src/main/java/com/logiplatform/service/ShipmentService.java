package com.logiplatform.service;

import com.logiplatform.model.NotificationResponse;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.ShipmentTrackingEvent;
import com.logiplatform.repository.ShipmentTrackingEventRepository;
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
import static com.logiplatform.dto.ShipmentDtos.*;
import static com.logiplatform.dto.CommandCenterShipmentDtos.UpdateRequest;

@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentTrackingEventRepository trackingEventRepository;
    private final NotificationService notificationService;
    private final FinancePostingService financePostingService;

    public ShipmentService(ShipmentRepository shipmentRepository,
            ShipmentTrackingEventRepository trackingEventRepository,
            NotificationService notificationService,
            FinancePostingService financePostingService) {
        this.shipmentRepository = shipmentRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.notificationService = notificationService;
        this.financePostingService = financePostingService;
    }

    @Transactional
    public ShipmentResponse create(CreateShipmentRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        if (shipmentRepository.existsByTenantIdAndReferenceCode(tenantId, request.referenceCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A shipment with reference code '" + request.referenceCode() + "' already exists");
        }

        TransportMode mode = parseTransportMode(request.transportMode());

        Shipment shipment = new Shipment(
                tenantId, request.referenceCode(), request.originAddress(), request.destinationAddress(),
                mode, request.carrierName(), request.carrierReferenceNumber());
        Shipment saved = shipmentRepository.save(shipment);

        // Every shipment's timeline starts here — this is what "high-end tracking" is
        // built out of: a consistent audit trail from booking to delivery.
        trackingEventRepository.save(new ShipmentTrackingEvent(
                tenantId, saved.getId(), TrackingEventType.BOOKED, request.originAddress(),
                "Shipment booked (" + mode + ")", Instant.now()));

        return ShipmentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<ShipmentResponse> list(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return shipmentRepository.findAllByTenantId(tenantId, pageable).map(ShipmentResponse::from);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(UUID shipmentId) {
        Shipment shipment = findOwned(shipmentId);
        return ShipmentResponse.from(shipment);
    }

    @Transactional
    public ShipmentResponse updateStatus(UUID shipmentId, UpdateStatusRequest request) {
        Shipment shipment = findOwned(shipmentId);

        ShipmentStatus newStatus;
        try {
            newStatus = ShipmentStatus.valueOf(request.status().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + request.status());
        }

        shipment.updateStatus(newStatus);
        Shipment saved = shipmentRepository.save(shipment);

        if (newStatus == ShipmentStatus.DELIVERED) {
            notificationService.notify(shipmentId, shipment.getNotificationEmail(),
                    "Your shipment " + shipment.getReferenceCode() + " has been delivered",
                    "Good news — shipment " + shipment.getReferenceCode() + " (" + shipment.getOriginAddress()
                            + " to " + shipment.getDestinationAddress() + ") has been marked delivered.");
        }

        return ShipmentResponse.from(saved);
    }

    @Transactional
    public ShipmentResponse updateCommandCenter(UUID shipmentId, UpdateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        }

        Shipment shipment = findOwned(shipmentId);
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
        if (request.shipmentStatus() != null && !request.shipmentStatus().isBlank()) {
            shipment.setOperationalStatus(request.shipmentStatus());
        }

        Shipment saved = shipmentRepository.save(shipment);
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

        TrackingEventType type;
        try {
            type = TrackingEventType.valueOf(request.eventType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid eventType: " + request.eventType());
        }

        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();
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
        Shipment shipment = findOwned(shipmentId);
        shipment.setWeightKg(request.weightKg());
        return ShipmentResponse.from(shipmentRepository.save(shipment));
    }

    @Transactional
    public ShipmentResponse updateNotificationEmail(UUID shipmentId, UpdateNotificationEmailRequest request) {
        Shipment shipment = findOwned(shipmentId);
        shipment.setNotificationEmail(request.notificationEmail());
        return ShipmentResponse.from(shipmentRepository.save(shipment));
    }

    @Transactional
    public ShipmentResponse updateFlightNumber(UUID shipmentId, UpdateFlightNumberRequest request) {
        Shipment shipment = findOwned(shipmentId);
        shipment.setFlightNumber(request.flightNumber());
        return ShipmentResponse.from(shipmentRepository.save(shipment));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> notificationHistory(UUID shipmentId, Pageable pageable) {
        findOwned(shipmentId); // enforce ownership before delegating
        return notificationService.history(shipmentId, pageable).map(NotificationResponse::from);
    }

    private Shipment findOwned(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        return shipmentRepository.findByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
