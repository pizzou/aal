package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import com.logiplatform.service.control.BookingStateMachineService;
import com.logiplatform.service.control.ReconciliationTaskService;
import com.logiplatform.integration.control.ExternalOperationException;
import com.logiplatform.integration.control.IntegrationMetricsService;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.security.core.context.SecurityContextHolder;
import com.logiplatform.security.TenantPrincipal;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.logiplatform.dto.AirCargoDtos.*;

@Service
public class AirCargoBookingService {
    private final AirCargoBookingRepository bookings;
    private final AirCargoFlightRepository flights;
    private final ShipmentService shipments;
    private final AirCargoProviderRegistry providers;
    private final AirlineIntegrationAttemptService attempts;
    private final AirlineDeadLetterService deadLetters;
    private final AuditService audit;
    private final BookingStateMachineService stateMachine;
    private final ReconciliationTaskService reconciliation;
    private final IntegrationMetricsService metrics;

    public AirCargoBookingService(AirCargoBookingRepository b, AirCargoFlightRepository f, ShipmentService s,
            AirCargoProviderRegistry p, AirlineIntegrationAttemptService a, AirlineDeadLetterService d,
            AuditService audit, BookingStateMachineService stateMachine, ReconciliationTaskService reconciliation,
            IntegrationMetricsService metrics) {
        bookings = b;
        flights = f;
        shipments = s;
        providers = p;
        attempts = a;
        deadLetters = d;
        this.audit = audit;
        this.stateMachine = stateMachine;
        this.reconciliation = reconciliation;
        this.metrics = metrics;
    }

    @Transactional
    public BookingResponse book(BookRequest r) {
        UUID tenant = TenantContext.getTenantId();
        validate(r);
        shipments.get(r.shipmentId());
        String idem = cleanKey(r.idempotencyKey());
        AirCargoBooking prior = bookings.findByTenantIdAndIdempotencyKey(tenant, idem).orElse(null);
        if (prior != null)
            return BookingResponse.from(prior);
        AirCargoProviderPort provider = providers.active();
        AirCargoProviderPort.ProviderCapabilities capabilities = provider.capabilities();
        boolean externalBooking = capabilities.booking();

        // A real external booking must originate from a live provider offer.
        // The providerReference is the selection token returned by the live search
        // (for CargoAi it contains flightUUID|rateId). Never silently turn an
        // externally-enabled booking into an AAL internal-capacity reservation.
        if (externalBooking && capabilities.scheduleSearch() &&
                (r.providerReference() == null || r.providerReference().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select a live airline availability result before creating the booking");
        }

        // Only AAL's persisted capacity may be reserved internally. External
        // providers own and validate their own live inventory.
        AirCargoFlight reserved = externalBooking ? null : findAndReserve(tenant, r);
        String source = externalBooking ? provider.providerCode() : "INTERNAL_CAPACITY";
        String flightNumber = safeFlightNumber(r.flightNumber());
        AirCargoBooking b = new AirCargoBooking(tenant, r.shipmentId(), r.carrierCode().trim().toUpperCase(Locale.ROOT),
                r.carrierName(), flightNumber, r.departureTime(), r.arrivalTime(),
                r.originCode().trim().toUpperCase(), r.destinationCode().trim().toUpperCase(), r.weightKg(),
                "REQUESTED", source, idem);
        b.setServiceLevel(r.serviceLevel());
        b.operation(idem, "BOOK");
        try {
            bookings.saveAndFlush(b);
            // Only move into provider-pending when an external provider will actually
            // receive the request, or when AAL has persisted capacity to confirm.
            if (externalBooking || reserved != null) {
                stateMachine.transition(b, "PENDING_PROVIDER", "BOOK_REQUESTED", correlationId());
            }
        } catch (DataIntegrityViolationException duplicate) {
            AirCargoBooking existing = bookings.findByTenantIdAndIdempotencyKey(tenant, idem)
                    .orElseThrow(() -> duplicate);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return BookingResponse.from(existing);
        }
        if (!externalBooking) {
            // AAL must remain usable when no airline API credentials are configured.
            // If a persisted flight exists, reserve its real AAL capacity. If it does
            // not exist, keep the request as an internal planning request instead of
            // returning a misleading 409. It can later be fulfilled/reconciled when
            // carrier capacity is ingested or an external provider is enabled.
            if (reserved != null) {
                String ref = "CAP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
                b.confirm(r.weightKg(), ref, ref, "INTERNAL_CAPACITY_RESERVATION");
                stateMachine.transition(b, "CONFIRMED", "INTERNAL_CAPACITY", correlationId());
            }
            syncShipmentFlightNumber(r.shipmentId(), flightNumber);
            BookingResponse response = BookingResponse.from(bookings.save(b));
            metrics.booking(response.status());
            audit(response.id(), "AIR_BOOKING_CREATED", "CREATE", response.status());
            return response;
        }
        String correlation = correlationId();
        UUID attempt = attempts.start(provider.providerCode(), "BOOK", idem, correlation, r.toString());
        try {
            String offerReference = extractOfferReference(r.providerReference());
            String rateReference = extractRateReference(r.providerReference());
            AirCargoProviderPort.BookingResult result = provider.book(new AirCargoProviderPort.BookingCommand(
                    idem,
                    r.shipmentId().toString(),
                    r.carrierCode().trim().toUpperCase(Locale.ROOT),
                    r.carrierName(),
                    flightNumber,
                    r.departureTime(),
                    r.arrivalTime(),
                    r.originCode().trim().toUpperCase(Locale.ROOT),
                    r.destinationCode().trim().toUpperCase(Locale.ROOT),
                    r.weightKg(),
                    r.serviceLevel(),
                    r.providerReference(),
                    offerReference,
                    rateReference));
            attempts.success(attempt, 200, result.rawResponse());
            String status = result.status() == null ? "PENDING" : result.status().toUpperCase(Locale.ROOT);
            if ("CONFIRMED".equals(status)
                    || "BOOKED".equals(status)
                    || "BOOKING_CONFIRMED".equals(status)) {
                b.confirm(result.confirmedWeightKg() == null ? r.weightKg() : result.confirmedWeightKg(),
                        result.providerReference(), result.confirmationNumber(), result.rawResponse());
                stateMachine.transition(b, "CONFIRMED", "PROVIDER_CONFIRMED", correlation);
            } else {
                b.pending(result.providerReference(), result.rawResponse());
            }
            syncShipmentFlightNumber(r.shipmentId(), flightNumber);
            BookingResponse response = BookingResponse.from(bookings.save(b));
            metrics.booking(response.status());
            audit(response.id(), "AIR_BOOKING_CREATED", "CREATE", response.status());
            return response;
        } catch (Exception ex) {
            attempts.failure(attempt, null, ex.getMessage());
            boolean unknown = ex instanceof ExternalOperationException e && e.outcomeUnknown();
            if (unknown) {
                stateMachine.transition(b, "UNKNOWN", "PROVIDER_OUTCOME_UNKNOWN", correlation);
                reconciliation.enqueue(provider.providerCode(), "BOOK", "AIR_CARGO_BOOKING", b.getId(), idem,
                        b.getProviderReference(), ex.getMessage());
                bookings.save(b);
                metrics.booking("UNKNOWN");
                throw new ResponseStatusException(HttpStatus.ACCEPTED,
                        "Airline booking outcome is unknown and has been queued for reconciliation");
            }
            deadLetters.enqueue(provider.providerCode(), "BOOK", idem, correlation, r.toString(), ex.getMessage());
            stateMachine.transition(b, "FAILED", "PROVIDER_REJECTED", correlation);
            bookings.save(b);
            metrics.booking("FAILED");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Airline booking failed; the request was placed in the integration dead-letter queue");
        }
    }

    @Transactional
    public BookingResponse amend(UUID id, AmendBookingRequest r) {
        UUID tenant = TenantContext.getTenantId();
        AirCargoBooking b = owned(id);
        if ("CANCELLED".equals(b.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled booking cannot be amended");
        String key = cleanKey(r.idempotencyKey());
        if (key.equals(b.getLastOperationKey()) && "AMEND".equals(b.getLastProviderOperation()))
            return BookingResponse.from(b);
        b.operation(key, "AMEND");
        AirCargoProviderPort p = providers.active();
        if (!p.capabilities().amendment())
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "The configured airline provider does not support booking amendments");
        stateMachine.transition(b, "AMENDMENT_PENDING", "AMEND_REQUESTED", correlationId());
        UUID attempt = attempts.start(p.providerCode(), "AMEND", key, UUID.randomUUID().toString(), r.toString());
        try {
            String amendedFlightNumber = safeFlightNumber(r.flightNumber());
            AirCargoProviderPort.BookingResult result = p
                    .amend(new AirCargoProviderPort.AmendmentCommand(key, b.getProviderReference(), amendedFlightNumber,
                            r.departureTime(), r.arrivalTime(), r.weightKg(), r.serviceLevel()));
            attempts.success(attempt, 200, result.rawResponse());
            b.amend(r.weightKg(), amendedFlightNumber, r.departureTime(), r.arrivalTime(), r.serviceLevel(),
                    result.providerReference(), result.rawResponse());
            stateMachine.transition(b, "CONFIRMED", "PROVIDER_AMENDMENT_CONFIRMED", attempt.toString());
            BookingResponse response = BookingResponse.from(bookings.save(b));
            metrics.booking(response.status());
            audit(response.id(), "AIR_BOOKING_AMENDED", "AMEND", response.status());
            return response;
        } catch (Exception ex) {
            attempts.failure(attempt, null, ex.getMessage());
            boolean unknown = ex instanceof ExternalOperationException e && e.outcomeUnknown();
            if (unknown) {
                stateMachine.transition(b, "UNKNOWN", "AMEND_OUTCOME_UNKNOWN", attempt.toString());
                reconciliation.enqueue(p.providerCode(), "AMEND", "AIR_CARGO_BOOKING", b.getId(), key,
                        b.getProviderReference(), ex.getMessage());
                bookings.save(b);
                throw new ResponseStatusException(HttpStatus.ACCEPTED,
                        "Amendment outcome is unknown and has been queued for reconciliation");
            }
            deadLetters.enqueue(p.providerCode(), "AMEND", key, attempt.toString(), r.toString(), ex.getMessage());
            stateMachine.transition(b, "FAILED", "AMEND_PROVIDER_REJECTED", attempt.toString());
            bookings.save(b);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Airline amendment failed; request queued for integration review");
        }
    }

    @Transactional
    public BookingResponse cancel(UUID id, CancelBookingRequest r) {
        UUID tenant = TenantContext.getTenantId();
        AirCargoBooking b = owned(id);
        if ("CANCELLED".equals(b.getStatus()))
            return BookingResponse.from(b);
        String key = cleanKey(r.idempotencyKey());
        AirCargoProviderPort p = providers.active();
        if (key.equals(b.getLastOperationKey()) && "CANCEL".equals(b.getLastProviderOperation()))
            return BookingResponse.from(b);
        stateMachine.transition(b, "CANCELLATION_PENDING", "CANCEL_REQUESTED", correlationId());
        b.operation(key, "CANCEL");
        if (p.capabilities().cancellation() && !"INTERNAL_CAPACITY".equals(b.getProvider())) {
            UUID attempt = attempts.start(p.providerCode(), "CANCEL", key, UUID.randomUUID().toString(), r.toString());
            try {
                AirCargoProviderPort.BookingResult result = p.cancel(
                        new AirCargoProviderPort.CancellationCommand(key, b.getProviderReference(), r.reason()));
                attempts.success(attempt, 200, result.rawResponse());
                b.cancel(r.reason(), result.rawResponse());
                stateMachine.transition(b, "CANCELLED", "PROVIDER_CONFIRMED_CANCELLATION", attempt.toString());
            } catch (Exception ex) {
                attempts.failure(attempt, null, ex.getMessage());
                boolean unknown = ex instanceof ExternalOperationException e && e.outcomeUnknown();
                if (unknown) {
                    stateMachine.transition(b, "UNKNOWN", "CANCEL_OUTCOME_UNKNOWN", attempt.toString());
                    reconciliation.enqueue(p.providerCode(), "CANCEL", "AIR_CARGO_BOOKING", b.getId(), key,
                            b.getProviderReference(), ex.getMessage());
                    bookings.save(b);
                    throw new ResponseStatusException(HttpStatus.ACCEPTED,
                            "Cancellation outcome is unknown and has been queued for reconciliation");
                }
                deadLetters.enqueue(p.providerCode(), "CANCEL", key, attempt.toString(), r.toString(), ex.getMessage());
                stateMachine.transition(b, "FAILED", "CANCEL_PROVIDER_REJECTED", attempt.toString());
                bookings.save(b);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Airline cancellation failed; request queued for integration review");
            }
        } else {
            b.cancel(r.reason(), "INTERNAL_BOOKING_CANCELLATION");
            stateMachine.transition(b, "CANCELLED", "INTERNAL_CANCELLATION", correlationId());
        }
        releaseCapacityIfInternal(tenant, b);
        BookingResponse response = BookingResponse.from(bookings.save(b));
        metrics.booking(response.status());
        audit(response.id(), "AIR_BOOKING_CANCELLED", "CANCEL", response.status());
        return response;
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> list() {
        return bookings.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId()).stream()
                .map(BookingResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID id) {
        return BookingResponse.from(owned(id));
    }

    private AirCargoBooking owned(UUID id) {
        return bookings.findByTenantIdAndId(TenantContext.getTenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private String correlationId() {
        return UUID.randomUUID().toString();
    }

    private AirCargoFlight findAndReserve(UUID tenant, BookRequest r) {
        List<AirCargoFlight> c = flights
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(tenant,
                        r.originCode().trim().toUpperCase(), r.destinationCode().trim().toUpperCase(),
                        r.departureTime().minus(2, ChronoUnit.MINUTES), r.departureTime().plus(2, ChronoUnit.MINUTES));
        if (c.isEmpty())
            return null;
        AirCargoFlight f = flights.findByTenantIdAndIdForUpdate(tenant, c.get(0).getId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "Flight disappeared during booking"));
        if (!f.reserve(r.weightKg()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient live capacity");
        flights.save(f);
        return f;
    }

    private void releaseCapacityIfInternal(UUID tenant, AirCargoBooking b) {
        if (!"INTERNAL_CAPACITY".equals(b.getProvider()))
            return;
        List<AirCargoFlight> c = flights
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(tenant,
                        b.getOriginCode(), b.getDestinationCode(), b.getDepartureTime().minus(2, ChronoUnit.MINUTES),
                        b.getDepartureTime().plus(2, ChronoUnit.MINUTES));
        if (!c.isEmpty()) {
            AirCargoFlight f = flights.findByTenantIdAndIdForUpdate(tenant, c.get(0).getId()).orElse(null);
            if (f != null) {
                f.release(b.getRequestedWeightKg());
                flights.save(f);
            }
        }
    }

    private void audit(UUID bookingId, String action, String operation, String status) {
        UUID tenant = TenantContext.getTenantId();
        UUID user = null;
        try {
            Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (p instanceof TenantPrincipal tp)
                user = tp.userId();
        } catch (Exception ignored) {
        }
        audit.record(tenant, user, action, "AIR_CARGO_BOOKING", bookingId, operation, "/api/air-cargo/bookings", null,
                null, 200, true, null, status, null);
    }

    private void syncShipmentFlightNumber(UUID shipmentId, String flightNumber) {
        String value;
        try {
            value = safeFlightNumber(flightNumber);
        } catch (ResponseStatusException ex) {
            return;
        }
        if (value.isBlank())
            return;
        try {
            com.logiplatform.dto.ShipmentDtos.ShipmentResponse current = shipments.get(shipmentId);
            if (current.flightNumber() == null || !value.equalsIgnoreCase(current.flightNumber().trim())) {
                shipments.updateFlightNumber(shipmentId,
                        new com.logiplatform.dto.ShipmentDtos.UpdateFlightNumberRequest(value));
            }
        } catch (Exception ignored) {
            // Booking persistence remains authoritative; a later shipment edit can
            // still assign the carrier flight number if the synchronization fails.
        }
    }

    private static void validate(BookRequest r) {
        if (r == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking request is required");
        if (r.shipmentId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment is required");
        if (r.carrierCode() == null || r.carrierCode().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrier code is required");
        if (r.flightNumber() == null || r.flightNumber().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flight number is required");
        if (r.originCode() == null || !r.originCode().trim().matches("[A-Za-z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Origin must be a valid 3-letter airport code");
        if (r.destinationCode() == null || !r.destinationCode().trim().matches("[A-Za-z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destination must be a valid 3-letter airport code");
        if (r.originCode().trim().equalsIgnoreCase(r.destinationCode().trim()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Origin and destination must differ");
        if (r.departureTime() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Departure time is required");
        if (r.departureTime().isBefore(Instant.now().minusSeconds(60)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot book a flight that has already departed");
        if (r.arrivalTime() != null && !r.arrivalTime().isAfter(r.departureTime()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arrival must be after departure");
        if (r.weightKg() == null || r.weightKg().signum() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cargo weight must be greater than zero");
    }

    private static String safeFlightNumber(String value) {
        String x = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (x.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid flight number is required");
        if (x.length() <= 20)
            return x;
        // Preserve the airline prefix and identifying suffix for legacy VARCHAR(20)
        // deployments. The DB migration may be wider, but the booking path remains
        // safe against older production schemas during rolling deployment.
        return x.substring(0, 8) + x.substring(x.length() - 12);
    }

    private static String extractOfferReference(String providerReference) {
        if (providerReference == null || providerReference.isBlank())
            return null;
        int separator = providerReference.indexOf('|');
        String offer = separator < 0 ? providerReference.trim()
                : providerReference.substring(0, separator).trim();
        return offer.isBlank() ? null : offer;
    }

    private static String extractRateReference(String providerReference) {
        if (providerReference == null || providerReference.isBlank())
            return null;
        int separator = providerReference.indexOf('|');
        if (separator < 0 || separator == providerReference.length() - 1)
            return null;
        String rate = providerReference.substring(separator + 1).trim();
        return rate.isBlank() ? null : rate;
    }

    private static String cleanKey(String key) {
        String x = key == null ? "" : key.trim();
        if (x.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid idempotency key is required");
        // Keep the database/provider idempotency key safely below even legacy
        // VARCHAR(20) deployments while preserving deterministic retry identity.
        if (x.length() <= 20)
            return x;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(x.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(20);
            for (int i = 0; i < 10; i++)
                out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to normalize idempotency key", e);
        }
    }
}
