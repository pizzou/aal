package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Live air-flight status and delay evaluation.
 *
 * <p>When an air booking was created through a live provider, the provider
 * reference is preferred over a flight-number lookup. This matters for
 * CargoAi because its Track & Trace workflow is keyed by the returned flight
 * UUID.</p>
 */
@Service
public class FlightDelayCheckService {
    private final FlightStatusPort flightStatusPort;
    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentEtaTrackingService etaTracking;
    private final AirCargoProviderRegistry providers;
    private final com.logiplatform.repository.AirCargoBookingRepository bookings;

    public FlightDelayCheckService(
            FlightStatusPort flightStatusPort,
            ShipmentService shipmentService,
            ShipmentRepository shipmentRepository,
            ShipmentEtaTrackingService etaTracking,
            AirCargoProviderRegistry providers,
            com.logiplatform.repository.AirCargoBookingRepository bookings) {
        this.flightStatusPort = flightStatusPort;
        this.shipmentService = shipmentService;
        this.shipmentRepository = shipmentRepository;
        this.etaTracking = etaTracking;
        this.providers = providers;
        this.bookings = bookings;
    }

    public FlightStatusPort.FlightStatusResult checkAndFlagDelay(UUID shipmentId) {
        Shipment shipment = shipmentRepository.findByIdAndTenantId(
                        shipmentId,
                        TenantContext.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Shipment not found"));

        var latestBooking = bookings
                .findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(
                        TenantContext.getTenantId(),
                        shipmentId)
                .stream()
                .findFirst()
                .orElse(null);

        String flightNumber = shipment.getFlightNumber();
        if ((flightNumber == null || flightNumber.isBlank()) && latestBooking != null) {
            flightNumber = latestBooking.getFlightNumber();
        }

        if (flightNumber == null || flightNumber.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This shipment has no flight number or air booking yet");
        }

        AirCargoProviderPort provider = providers.active();

        // Prefer the concrete provider reference for a live AAL air booking.
        if (latestBooking != null
                && latestBooking.getProviderReference() != null
                && !latestBooking.getProviderReference().isBlank()
                && provider.capabilities().flightStatus()
                && !"INTERNAL_CAPACITY".equalsIgnoreCase(latestBooking.getProvider())) {
            try {
                AirCargoProviderPort.FlightStatus live = provider
                        .getFlightStatusByProviderReference(latestBooking.getProviderReference());
                FlightStatusPort.FlightStatusResult result = applyLiveStatus(
                        shipmentId,
                        provider,
                        live);
                return result;
            } catch (UnsupportedOperationException ignored) {
                // Fall through to another status source.
            } catch (RuntimeException ignored) {
                // A transient provider failure must not remove the historical ETA fallback.
            }
        }

        Optional<FlightStatusPort.FlightStatusResult> external = flightStatusPort.getStatus(
                flightNumber,
                LocalDate.now().toString());
        if (external.isPresent()) {
            return external.get();
        }

        if (provider.capabilities().flightStatus()) {
            try {
                AirCargoProviderPort.FlightStatus live = provider.getFlightStatus(
                        flightNumber,
                        LocalDate.now().toString());
                return applyLiveStatus(shipmentId, provider, live);
            } catch (UnsupportedOperationException ignored) {
                // Provider requires an offer/reference-specific lookup.
            }
        }

        // Auditable local baseline when no live flight-status feed is reachable.
        java.time.Instant scheduledDeparture = shipment.getEtd();
        java.time.Instant scheduledArrival = shipment.getEta();
        if (latestBooking != null) {
            if (scheduledDeparture == null) {
                scheduledDeparture = latestBooking.getDepartureTime();
            }
            if (scheduledArrival == null) {
                scheduledArrival = latestBooking.getArrivalTime();
            }
        }

        if (scheduledDeparture == null && scheduledArrival == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No live flight-status provider is configured and this shipment has no schedule baseline");
        }

        return new FlightStatusPort.FlightStatusResult(
                "SCHEDULED",
                0,
                0,
                false,
                scheduledDeparture,
                null,
                null,
                scheduledArrival,
                null,
                null,
                "AAL_SCHEDULE_BASELINE",
                "Live provider status unavailable; showing the auditable AAL shipment/booking schedule baseline");
    }

    private FlightStatusPort.FlightStatusResult applyLiveStatus(
            UUID shipmentId,
            AirCargoProviderPort provider,
            AirCargoProviderPort.FlightStatus status) {
        etaTracking.apply(shipmentId, status, provider.providerCode());
        return new FlightStatusPort.FlightStatusResult(
                status.flightStatus(),
                status.departureDelayMinutes(),
                status.arrivalDelayMinutes(),
                status.departureDelayMinutes() >= 60 || status.arrivalDelayMinutes() >= 60,
                status.scheduledDeparture(),
                status.estimatedDeparture(),
                status.actualDeparture(),
                status.scheduledArrival(),
                status.estimatedArrival(),
                status.actualArrival(),
                status.providerEventId(),
                status.rawResponse());
    }
}
