package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.TransportMode;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.service.control.DistributedJobLockService;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Background live ETA poller for air shipments.
 *
 * <p>Provider-reference tracking is attempted first. This allows adapters such
 * as CargoAi to use their flight UUID rather than guessing a flight from a
 * flight number/date pair.</p>
 */
@Component
public class AirCargoTrackingScheduler {
    private final ShipmentRepository shipments;
    private final AirCargoBookingRepository bookings;
    private final AirCargoProviderRegistry registry;
    private final ShipmentEtaTrackingService eta;
    private final DistributedJobLockService jobLocks;
    private final UUID tenantId;
    private final boolean enabled;

    public AirCargoTrackingScheduler(
            ShipmentRepository shipments,
            AirCargoBookingRepository bookings,
            AirCargoProviderRegistry registry,
            ShipmentEtaTrackingService eta,
            DistributedJobLockService jobLocks,
            @Value("${app.single-tenant.id}") String tenant,
            @Value("${aircargo.eta-poll.enabled:false}") boolean enabled) {
        this.shipments = shipments;
        this.bookings = bookings;
        this.registry = registry;
        this.eta = eta;
        this.jobLocks = jobLocks;
        this.tenantId = UUID.fromString(tenant);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${aircargo.eta-poll.interval-ms:300000}")
    public void poll() {
        if (!enabled) {
            return;
        }

        AirCargoProviderPort provider = registry.active();
        if (!provider.capabilities().flightStatus()) {
            return;
        }

        TenantContext.setTenantId(tenantId);
        String owner = jobLocks.tryAcquire(
                "air-cargo-eta-poll",
                Duration.ofMinutes(5));

        if (owner == null) {
            TenantContext.clear();
            return;
        }

        try {
            Set<UUID> ids = new LinkedHashSet<>();
            for (ShipmentStatus status : List.of(
                    ShipmentStatus.BOOKED,
                    ShipmentStatus.IN_TRANSIT,
                    ShipmentStatus.PLANNING)) {
                for (Shipment shipment : shipments.findAllByTenantIdAndWeightKgIsNotNullAndStatus(
                        tenantId,
                        status)) {
                    if (shipment.getTransportMode() == TransportMode.AIR
                            && shipment.getFlightNumber() != null
                            && !shipment.getFlightNumber().isBlank()) {
                        ids.add(shipment.getId());
                    }
                }
            }

            for (UUID id : ids) {
                try {
                    Shipment shipment = shipments
                            .findByIdAndTenantId(id, tenantId)
                            .orElse(null);
                    if (shipment == null) {
                        continue;
                    }

                    var latestBooking = bookings
                            .findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(
                                    tenantId,
                                    id)
                            .stream()
                            .findFirst()
                            .orElse(null);

                    AirCargoProviderPort.FlightStatus live;
                    if (latestBooking != null
                            && latestBooking.getProviderReference() != null
                            && !latestBooking.getProviderReference().isBlank()
                            && !"INTERNAL_CAPACITY".equalsIgnoreCase(latestBooking.getProvider()) ) {
                        try {
                            live = provider.getFlightStatusByProviderReference(
                                    latestBooking.getProviderReference());
                        } catch (UnsupportedOperationException ex) {
                            live = provider.getFlightStatus(
                                    shipment.getFlightNumber(),
                                    LocalDate.now().toString());
                        }
                    } else {
                        live = provider.getFlightStatus(
                                shipment.getFlightNumber(),
                                LocalDate.now().toString());
                    }

                    eta.apply(id, live, provider.providerCode());
                } catch (Exception ignored) {
                    // One stale provider response must not stop polling other shipments.
                }
            }
        } finally {
            jobLocks.release("air-cargo-eta-poll", owner);
            TenantContext.clear();
        }
    }
}
