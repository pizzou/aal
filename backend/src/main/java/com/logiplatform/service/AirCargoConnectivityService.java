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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronizes live airline/provider offers into the auditable AAL snapshot.
 * Search fans out to configured live providers and keeps each provider's offers
 * separate so one carrier/network cannot overwrite another provider's rate.
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
        String carrier = request.carrierCode().trim().toUpperCase();
        String flightNumber = request.flightNumber().trim().toUpperCase();

        var existing = repo
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant,
                        origin,
                        destination,
                        request.departureTime().minusSeconds(1),
                        request.departureTime().plusSeconds(1))
                .stream()
                .filter(f -> carrier.equalsIgnoreCase(f.getCarrierCode()))
                .filter(f -> flightNumber.equalsIgnoreCase(f.getFlightNumber()))
                .filter(f -> "INTERNAL".equalsIgnoreCase(f.getProviderCode()))
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
                carrier,
                request.carrierName(),
                flightNumber,
                origin,
                destination,
                request.departureTime(),
                request.arrivalTime(),
                request.totalCapacityKg(),
                request.availableCapacityKg(),
                "INTERNAL"));
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

        if (!normalizedOrigin.matches("[A-Z]{3}") || !normalizedDestination.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Origin and destination must be valid 3-letter airport codes");
        }
        if (normalizedOrigin.equals(normalizedDestination)) {
            throw new IllegalArgumentException("Origin and destination must differ");
        }
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("Flight search end time must be after start time");
        }
        if (weightKg == null || weightKg.signum() <= 0) {
            throw new IllegalArgumentException("weightKg must be positive");
        }

        List<AirCargoProviderPort> searchProviders = providers.searchProviders();
        if (!searchProviders.isEmpty()) {
            Map<String, AirCargoFlight> deduped = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();

            for (AirCargoProviderPort provider : searchProviders) {
                try {
                    List<AirCargoProviderPort.FlightOffer> offers = provider.searchFlights(
                            normalizedOrigin,
                            normalizedDestination,
                            from,
                            to,
                            weightKg);
                    if (offers == null) continue;
                    for (AirCargoProviderPort.FlightOffer offer : offers) {
                        if (offer == null || offer.departure() == null || offer.arrival() == null) continue;
                        if (offer.totalCapacityKg() == null || offer.availableCapacityKg() == null) continue;
                        if (offer.availableCapacityKg().signum() < 0
                                || offer.totalCapacityKg().signum() < 0
                                || offer.availableCapacityKg().compareTo(offer.totalCapacityKg()) > 0) continue;
                        if (offer.availableCapacityKg().compareTo(weightKg) < 0) continue;

                        AirCargoFlight flight = upsertOffer(tenant, provider, offer);
                        String key = offerKey(provider, offer);
                        deduped.put(key, flight);
                    }
                } catch (Exception ex) {
                    failures.add(provider.providerCode() + ": " + safeMessage(ex));
                }
            }

            List<AirCargoFlight> result = new ArrayList<>(deduped.values());
            result.sort(Comparator.comparing(AirCargoFlight::getDepartureTime));
            if (!result.isEmpty()) return result;
            if (!failures.isEmpty()) {
                throw new IllegalStateException("All live airline searches failed: " + String.join(" | ", failures));
            }
        }

        // No configured live search provider: return auditable locally-ingested data.
        return repo
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant,
                        normalizedOrigin,
                        normalizedDestination,
                        from,
                        to)
                .stream()
                .filter(f -> f.getAvailableCapacityKg().compareTo(weightKg) >= 0)
                .sorted(Comparator.comparing(AirCargoFlight::getDepartureTime))
                .toList();
    }

    public CapacityResponse capacity(String flightNumber, Instant date) {
        List<AirCargoProviderPort> candidates = providers.searchProviders().stream()
                .filter(p -> p.capabilities().liveCapacity())
                .toList();

        for (AirCargoProviderPort provider : candidates) {
            try {
                var live = provider.capacity(flightNumber, date);
                return new CapacityResponse(
                        live.flightNumber(),
                        live.availableCapacityKg(),
                        live.totalCapacityKg(),
                        live.asOf(),
                        live.source());
            } catch (UnsupportedOperationException ignored) {
                // Some carriers expose capacity only in their Availability/Rate result.
            } catch (Exception ignored) {
                // Try the next configured live provider before falling back to the snapshot.
            }
        }

        List<AirCargoFlight> local = repo
                .findAllByTenantIdAndFlightNumberAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        TenantContext.getTenantId(),
                        flightNumber,
                        date.minusSeconds(1),
                        date.plusSeconds(86400));

        AirCargoFlight flight = local.stream()
                .filter(x -> x.getFlightNumber().equalsIgnoreCase(flightNumber))
                .filter(x -> x.getAvailableCapacityKg() != null)
                .sorted(Comparator.comparing(AirCargoFlight::getUpdatedAt).reversed())
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
                flight.getUpdatedAt(),
                flight.getSource());
    }

    private AirCargoFlight upsertOffer(
            UUID tenant,
            AirCargoProviderPort provider,
            AirCargoProviderPort.FlightOffer offer) {
        List<AirCargoFlight> matches = repo
                .findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant,
                        offer.origin(),
                        offer.destination(),
                        offer.departure().minusSeconds(1),
                        offer.departure().plusSeconds(1));

        AirCargoFlight existing = matches.stream()
                .filter(x -> provider.providerCode().equalsIgnoreCase(x.getProviderCode()))
                .filter(x -> x.getCarrierCode().equalsIgnoreCase(offer.carrierCode()))
                .filter(x -> x.getFlightNumber().equalsIgnoreCase(offer.flightNumber()))
                .filter(x -> safeEquals(x.getProviderReference(), offer.providerReference()))
                .findFirst()
                .orElse(null);

        if (existing == null) {
            existing = new AirCargoFlight(
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
        } else {
            existing.refreshCapacity(
                    offer.totalCapacityKg(),
                    offer.availableCapacityKg(),
                    offer.arrival(),
                    provider.providerCode());
        }

        existing.setProviderOffer(
                offer.providerReference(),
                offer.rateId(),
                offer.rateName(),
                offer.currency(),
                offer.totalPrice(),
                offer.bookable(),
                offer.availableReason());
        return repo.save(existing);
    }

    private static String offerKey(AirCargoProviderPort provider, AirCargoProviderPort.FlightOffer offer) {
        return provider.providerCode() + "|"
                + safe(offer.providerReference()) + "|"
                + safe(offer.carrierCode()) + "|"
                + safe(offer.flightNumber()) + "|"
                + String.valueOf(offer.departure().toEpochMilli());
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || a.isBlank()) return b == null || b.isBlank();
        return a.equals(b);
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    public record CapacityResponse(
            String flightNumber,
            BigDecimal availableCapacityKg,
            BigDecimal totalCapacityKg,
            Instant asOf,
            String source) {}
}
