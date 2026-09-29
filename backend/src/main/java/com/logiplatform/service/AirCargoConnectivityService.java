package com.logiplatform.service;

import com.logiplatform.dto.AirCargoDtos;
import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Synchronizes live airline/provider offers into the auditable AAL snapshot.
 * Search fans out to configured live providers and keeps each provider's offers
 * separate so one carrier/network cannot overwrite another provider's rate.
 */
@Service
public class AirCargoConnectivityService {
    private final AirCargoFlightRepository repo;
    private final AirCargoProviderRegistry providers;
    private final ExecutorService executor;

    public AirCargoConnectivityService(
            AirCargoFlightRepository repo,
            AirCargoProviderRegistry providers,
            @Qualifier("airCargoExecutor") ExecutorService executor) {
        this.repo = repo;
        this.providers = providers;
        this.executor = executor;
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

            List<CompletableFuture<ProviderSearchResult>> futures = searchProviders.stream()
                    .map(provider -> CompletableFuture.supplyAsync(
                            () -> searchProvider(tenant, provider, normalizedOrigin, normalizedDestination, from, to, weightKg),
                            executor))
                    .toList();

            for (CompletableFuture<ProviderSearchResult> future : futures) {
                ProviderSearchResult providerResult = future.join();
                if (providerResult.failure() != null) {
                    failures.add(providerResult.failure());
                    continue;
                }
                for (Map.Entry<String, AirCargoFlight> entry : providerResult.flights().entrySet()) {
                    deduped.put(entry.getKey(), entry.getValue());
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

    private ProviderSearchResult searchProvider(
            UUID tenant,
            AirCargoProviderPort provider,
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {
        TenantContext.setTenantId(tenant);
        try {
            Map<String, AirCargoFlight> flights = new LinkedHashMap<>();
            List<AirCargoProviderPort.FlightOffer> offers = provider.searchFlights(
                    origin, destination, from, to, weightKg);
            if (offers != null) {
                for (AirCargoProviderPort.FlightOffer offer : offers) {
                    if (!validOffer(offer, weightKg)) continue;
                    AirCargoFlight flight = upsertOffer(tenant, provider, offer);
                    flights.put(offerKey(provider, offer), flight);
                }
            }
            return new ProviderSearchResult(provider.providerCode(), flights, null);
        } catch (Exception ex) {
            return new ProviderSearchResult(
                    provider.providerCode(),
                    Map.of(),
                    provider.providerCode() + ": " + safeMessage(ex));
        } finally {
            TenantContext.clear();
        }
    }

    private boolean validOffer(AirCargoProviderPort.FlightOffer offer, BigDecimal weightKg) {
        return offer != null
                && offer.departure() != null
                && offer.arrival() != null
                && offer.totalCapacityKg() != null
                && offer.availableCapacityKg() != null
                && offer.availableCapacityKg().signum() >= 0
                && offer.totalCapacityKg().signum() >= 0
                && offer.availableCapacityKg().compareTo(offer.totalCapacityKg()) <= 0
                && offer.availableCapacityKg().compareTo(weightKg) >= 0;
    }

    private AirCargoFlight upsertOffer(
            UUID tenant,
            AirCargoProviderPort provider,
            AirCargoProviderPort.FlightOffer offer) {
        String providerCode = provider.providerCode();
        String providerReference = offer.providerReference();
        AirCargoFlight existing = null;

        if (providerReference != null && !providerReference.isBlank()) {
            existing = repo.findFirstByTenantIdAndProviderCodeAndProviderReference(
                    tenant, providerCode, providerReference).orElse(null);
        }

        if (existing == null) {
            existing = repo.findFirstByTenantIdAndProviderCodeAndCarrierCodeAndFlightNumberAndDepartureTimeAndRateId(
                    tenant,
                    providerCode,
                    offer.carrierCode(),
                    offer.flightNumber(),
                    offer.departure(),
                    offer.rateId()).orElse(null);
        }

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
                    "EXTERNAL",
                    provider.providerCode());
        } else {
            existing.refreshCapacity(
                    offer.totalCapacityKg(),
                    offer.availableCapacityKg(),
                    offer.arrival(),
                    "EXTERNAL");
            existing.assignProviderCode(provider.providerCode());
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

    private record ProviderSearchResult(
            String providerCode,
            Map<String, AirCargoFlight> flights,
            String failure) {
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
