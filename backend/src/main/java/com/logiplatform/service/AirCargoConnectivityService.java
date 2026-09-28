package com.logiplatform.service;

import com.logiplatform.dto.AirCargoDtos;
import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Synchronizes live provider offers into the AAL operational flight snapshot.
 *
 * <p>Provider APIs are the source of truth for live airline availability. The
 * local air-cargo flight table is only the auditable operational snapshot used
 * by the rest of the platform and as a fallback when a provider cannot expose
 * standalone capacity by flight number.</p>
 */
@Service
public class AirCargoConnectivityService {
    private final AirCargoFlightRepository repo;
    private final AirCargoProviderRegistry providers;

    public AirCargoConnectivityService(
            AirCargoFlightRepository repo,
            AirCargoProviderRegistry providers) {
        this.repo = repo;
        this.providers = providers;
    }

    public AirCargoFlight ingest(AirCargoDtos.FlightIngestRequest request) {
        UUID tenant = TenantContext.getTenantId();
        String origin = request.originCode().trim().toUpperCase();
        String destination = request.destinationCode().trim().toUpperCase();

        var existing = repo
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant,
                        origin,
                        destination,
                        request.departureTime().minusSeconds(1),
                        request.departureTime().plusSeconds(1))
                .stream()
                .filter(f -> f.getCarrierCode().equalsIgnoreCase(request.carrierCode()))
                .filter(f -> f.getFlightNumber().equalsIgnoreCase(request.flightNumber()))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            existing.refreshCapacity(
                    request.totalCapacityKg(),
                    request.availableCapacityKg(),
                    request.arrivalTime(),
                    "INGESTED");
            return repo.save(existing);
        }

        return repo.save(new AirCargoFlight(
                tenant,
                request.carrierCode(),
                request.carrierName(),
                request.flightNumber(),
                origin,
                destination,
                request.departureTime(),
                request.arrivalTime(),
                request.totalCapacityKg(),
                request.availableCapacityKg(),
                "INGESTED"));
    }

    public List<AirCargoFlight> schedules(
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {

        UUID tenant = TenantContext.getTenantId();
        String normalizedOrigin = origin == null ? "" : origin.trim().toUpperCase();
        String normalizedDestination = destination == null ? "" : destination.trim().toUpperCase();

        if (!normalizedOrigin.matches("[A-Z]{3}")
                || !normalizedDestination.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "Origin and destination must be valid 3-letter airport codes");
        }
        if (normalizedOrigin.equals(normalizedDestination)) {
            throw new IllegalArgumentException("Origin and destination must differ");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException(
                    "Flight search end time must be after start time");
        }
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new IllegalArgumentException("weightKg must be positive");
        }

        AirCargoProviderPort provider = providers.active();
        if (provider.capabilities().scheduleSearch()) {
            List<AirCargoFlight> result = new ArrayList<>();

            for (AirCargoProviderPort.FlightOffer offer : provider.searchFlights(
                    normalizedOrigin,
                    normalizedDestination,
                    from,
                    to,
                    weightKg)) {

                var existing = repo
                        .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                                tenant,
                                normalizedOrigin,
                                normalizedDestination,
                                offer.departure().minusSeconds(1),
                                offer.departure().plusSeconds(1))
                        .stream()
                        .filter(x -> x.getCarrierCode().equalsIgnoreCase(offer.carrierCode()))
                        .filter(x -> x.getFlightNumber().equalsIgnoreCase(offer.flightNumber()))
                        .findFirst()
                        .orElse(null);

                if (existing != null) {
                    existing.refreshCapacity(
                            offer.totalCapacityKg(),
                            offer.availableCapacityKg(),
                            offer.arrival(),
                            provider.providerCode());
                    existing.setProviderOffer(
                            offer.providerReference(),
                            offer.rateId(),
                            offer.rateName(),
                            offer.currency(),
                            offer.totalPrice(),
                            offer.bookable(),
                            offer.availableReason());
                    result.add(repo.save(existing));
                } else {
                    var created = new AirCargoFlight(
                            tenant,
                            offer.carrierCode(),
                            offer.carrierName(),
                            offer.flightNumber(),
                            offer.origin(),
                            offer.destination(),
                            offer.departure(),
                            offer.arrival(),
                            offer.totalCapacityKg(),
                            offer.availableCapacityKg(),
                            provider.providerCode());
                    created.setProviderOffer(
                            offer.providerReference(),
                            offer.rateId(),
                            offer.rateName(),
                            offer.currency(),
                            offer.totalPrice(),
                            offer.bookable(),
                            offer.availableReason());
                    result.add(repo.save(created));
                }
            }
            return result;
        }

        return repo
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant,
                        normalizedOrigin,
                        normalizedDestination,
                        from,
                        to)
                .stream()
                .filter(f -> f.getAvailableCapacityKg().compareTo(weightKg) >= 0)
                .toList();
    }

    public CapacityResponse capacity(
            String flightNumber,
            Instant date) {

        AirCargoProviderPort provider = providers.active();

        if (provider.capabilities().liveCapacity()) {
            try {
                var live = provider.capacity(flightNumber, date);
                return new CapacityResponse(
                        live.flightNumber(),
                        live.availableCapacityKg(),
                        live.totalCapacityKg(),
                        live.asOf(),
                        live.source());
            } catch (UnsupportedOperationException ignored) {
                // Some live providers expose capacity only inside search/rate
                // responses. Use the latest persisted live offer below.
            }
        }

        List<AirCargoFlight> local = repo
                .findAllByTenantIdAndFlightNumberAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        TenantContext.getTenantId(),
                        flightNumber,
                        date.minusSeconds(1),
                        date.plusSeconds(86400));

        var flight = local.stream()
                .filter(x -> x.getFlightNumber().equalsIgnoreCase(flightNumber))
                .findFirst()
                .orElse(null);

        if (flight == null) {
            return new CapacityResponse(
                    flightNumber,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    Instant.now(),
                    "NOT_CONFIGURED");
        }

        return new CapacityResponse(
                flightNumber,
                flight.getAvailableCapacityKg(),
                flight.getTotalCapacityKg(),
                Instant.now(),
                flight.getSource());
    }

    public record CapacityResponse(
            String flightNumber,
            BigDecimal availableCapacityKg,
            BigDecimal totalCapacityKg,
            Instant asOf,
            String source) {
    }
}
