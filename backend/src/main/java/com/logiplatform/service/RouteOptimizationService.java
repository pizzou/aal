package com.logiplatform.service;

import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RouteOptimizationService {
    private final AirCargoFlightRepository flights;

    public RouteOptimizationService(AirCargoFlightRepository flights) {
        this.flights = flights;
    }

    public List<RouteOption> optimize(
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {

        UUID tenant = TenantContext.getTenantId();
        String o = origin.trim().toUpperCase();
        String d = destination.trim().toUpperCase();

        List<AirCargoFlight> firstLegs = flights
                .findAllByTenantIdAndOriginCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                        tenant, o, from, to);

        Set<String> connectionOrigins = new HashSet<>();
        for (AirCargoFlight flight : firstLegs) {
            if (!d.equalsIgnoreCase(flight.getDestinationCode())
                    && flight.getAvailableCapacityKg().compareTo(weightKg) >= 0) {
                connectionOrigins.add(flight.getDestinationCode());
            }
        }

        Map<String, List<AirCargoFlight>> secondLegsByOrigin = new HashMap<>();
        if (!connectionOrigins.isEmpty()) {
            // The repository method takes List<String>. Keep the Set for
            // deduplication, then convert it once at the repository boundary.
            List<String> originList = new ArrayList<>(connectionOrigins);

            List<AirCargoFlight> secondLegs = flights
                    .findAllByTenantIdAndOriginCodeInAndDepartureTimeBetweenOrderByDepartureTimeAsc(
                            tenant, originList, from, to);

            for (AirCargoFlight flight : secondLegs) {
                secondLegsByOrigin
                        .computeIfAbsent(flight.getOriginCode(), ignored -> new ArrayList<>())
                        .add(flight);
            }
        }

        List<RouteOption> out = new ArrayList<>();
        for (AirCargoFlight first : firstLegs) {
            if (first.getAvailableCapacityKg().compareTo(weightKg) < 0) {
                continue;
            }

            if (first.getDestinationCode().equalsIgnoreCase(d)) {
                out.add(score(List.of(first), weightKg));
                continue;
            }

            Instant minimumSecondDeparture = (first.getArrivalTime() == null
                    ? first.getDepartureTime()
                    : first.getArrivalTime()).plus(Duration.ofMinutes(60));

            for (AirCargoFlight second : secondLegsByOrigin
                    .getOrDefault(first.getDestinationCode(), List.of())) {
                if (!second.getDestinationCode().equalsIgnoreCase(d)
                        || second.getDepartureTime().isBefore(minimumSecondDeparture)
                        || second.getAvailableCapacityKg().compareTo(weightKg) < 0) {
                    continue;
                }
                out.add(score(List.of(first, second), weightKg));
            }
        }

        return out.stream()
                .sorted(Comparator.comparingDouble(RouteOption::score))
                .limit(20)
                .toList();
    }

    private RouteOption score(List<AirCargoFlight> path, BigDecimal weightKg) {
        AirCargoFlight first = path.get(0);
        AirCargoFlight last = path.get(path.size() - 1);
        long mins = last.getArrivalTime() == null
                ? 0
                : Duration.between(first.getDepartureTime(), last.getArrivalTime()).toMinutes();
        double scarcity = path.stream()
                .mapToDouble(flight -> flight.getTotalCapacityKg().signum() == 0
                        ? 1
                        : flight.getAvailableCapacityKg()
                                .divide(flight.getTotalCapacityKg(), 6, RoundingMode.HALF_UP)
                                .doubleValue())
                .average()
                .orElse(0);
        double score = mins + (1 - scarcity) * 240 + (path.size() - 1) * 180;
        String flightNumbers = path.stream()
                .map(AirCargoFlight::getFlightNumber)
                .reduce((a, b) -> a + " + " + b)
                .orElse("");
        String carriers = path.stream()
                .map(flight -> flight.getCarrierName() == null ? flight.getCarrierCode() : flight.getCarrierName())
                .reduce((a, b) -> a + " + " + b)
                .orElse("");
        return new RouteOption(
                first.getId(),
                first.getCarrierCode(),
                carriers,
                flightNumbers,
                first.getOriginCode(),
                last.getDestinationCode(),
                first.getDepartureTime(),
                last.getArrivalTime(),
                path.stream()
                        .map(AirCargoFlight::getAvailableCapacityKg)
                        .min(BigDecimal::compareTo)
                        .orElse(BigDecimal.ZERO),
                score);
    }

    public record RouteOption(
            UUID flightId,
            String carrierCode,
            String carrierName,
            String flightNumber,
            String origin,
            String destination,
            Instant departure,
            Instant arrival,
            BigDecimal availableCapacityKg,
            double score) {
    }
}
