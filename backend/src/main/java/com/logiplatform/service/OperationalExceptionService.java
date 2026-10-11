package com.logiplatform.service;

import com.logiplatform.dto.OperationsDtos.ExceptionRequest;
import com.logiplatform.dto.OperationsDtos.ExceptionResponse;
import com.logiplatform.dto.OperationsDtos.ResolveExceptionRequest;
import com.logiplatform.model.OperationalException;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.OperationalExceptionRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.repository.TripShipmentRepository;
import com.logiplatform.repository.VehicleRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped exception lifecycle. Notification delivery must never prevent
 * the operational incident itself from being recorded. */
@Service
public class OperationalExceptionService {
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final int MAX_DESCRIPTION = 8000;

    private final OperationalExceptionRepository repo;
    private final ShipmentRepository shipments;
    private final TripRepository trips;
    private final VehicleRepository vehicles;
    private final TripShipmentRepository tripShipments;
    private final NotificationService notifications;

    public OperationalExceptionService(OperationalExceptionRepository repo,
            ShipmentRepository shipments, TripRepository trips, VehicleRepository vehicles,
            TripShipmentRepository tripShipments, NotificationService notifications) {
        this.repo = repo;
        this.shipments = shipments;
        this.trips = trips;
        this.vehicles = vehicles;
        this.tripShipments = tripShipments;
        this.notifications = notifications;
    }

    @Transactional
    public ExceptionResponse open(ExceptionRequest request) {
        UUID tenant = requireTenant();
        if (request == null) throw bad("Exception details are required");
        String type = required(request.type(), "Exception type", 80).toUpperCase(Locale.ROOT);
        String severity = required(request.severity(), "Severity", 20).toUpperCase(Locale.ROOT);
        String description = required(request.description(), "Description", MAX_DESCRIPTION);
        if (!SEVERITIES.contains(severity)) {
            throw bad("Severity must be LOW, MEDIUM, HIGH, or CRITICAL");
        }
        String owner = normalize(request.owner());
        if (owner != null && owner.length() > 160) throw bad("Owner must not exceed 160 characters");

        Shipment shipment = null;
        if (request.shipmentId() != null) {
            shipment = shipments.findByIdAndTenantId(request.shipmentId(), tenant)
                    .orElseThrow(() -> notFound("Shipment not found"));
        }
        if (request.tripId() != null) {
            trips.findByIdAndTenantId(request.tripId(), tenant)
                    .orElseThrow(() -> notFound("Trip not found"));
            if (request.shipmentId() != null && !tripShipments.existsByTenantIdAndId_TripIdAndId_ShipmentId(
                    tenant, request.tripId(), request.shipmentId())) {
                throw bad("The selected shipment is not assigned to the selected trip");
            }
        }
        if (request.vehicleId() != null) {
            vehicles.findByIdAndTenantId(request.vehicleId(), tenant)
                    .orElseThrow(() -> notFound("Vehicle not found"));
        }

        OperationalException saved = repo.saveAndFlush(new OperationalException(
                tenant, request.shipmentId(), request.tripId(), request.vehicleId(),
                type, severity, owner, description));

        // Dispatch the alert only after the incident commits. A rollback must
        // never send an alert for an incident that was not persisted, and an
        // email-provider failure must never roll back the incident itself.
        if (shipment != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID shipmentId = shipment.getId();
            String recipient = shipment.getNotificationEmail();
            String subject = "Logistics exception: " + shipment.getReferenceCode();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        notifications.notifyAfterCommit(shipmentId, recipient, subject, description);
                    } catch (RuntimeException ignored) {
                        // Operational record remains authoritative; notification status
                        // and delivery monitoring are handled independently.
                    }
                }
            });
        }
        return ExceptionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ExceptionResponse> list(String status) {
        UUID tenant = requireTenant();
        String normalized = normalize(status);
        List<OperationalException> rows;
        if (normalized == null) {
            rows = repo.findAllByTenantIdOrderByCreatedAtDesc(tenant);
        } else {
            normalized = normalized.toUpperCase(Locale.ROOT);
            if (!Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED").contains(normalized)) {
                throw bad("Invalid exception status");
            }
            rows = repo.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenant, normalized);
        }
        return rows.stream().map(ExceptionResponse::from).toList();
    }

    @Transactional
    public ExceptionResponse resolve(UUID id, ResolveExceptionRequest request) {
        UUID tenant = requireTenant();
        if (request == null) throw bad("Resolution details are required");
        String resolution = required(request.resolution(), "Resolution", 4000);
        String action = normalize(request.actionTaken());
        if (action != null && action.length() > 4000) throw bad("Action taken must not exceed 4000 characters");

        OperationalException exception = repo.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> notFound("Exception not found"));
        if ("RESOLVED".equalsIgnoreCase(exception.getStatus()) || "CLOSED".equalsIgnoreCase(exception.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exception is already resolved");
        }
        exception.action(action);
        exception.resolve(resolution);
        return ExceptionResponse.from(repo.save(exception));
    }

    private static UUID requireTenant() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is missing");
        return tenant;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank()) throw bad(label + " is required");
        String result = value.trim();
        if (result.length() > max) throw bad(label + " must not exceed " + max + " characters");
        return result;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
