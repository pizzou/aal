package com.logiplatform.controller;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.service.AirlineDeadLetterService;
import com.logiplatform.service.AirlineIntegrationAttemptService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/air-cargo/integration")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','AIR_CARGO')")
public class AirCargoIntegrationController {
    private final AirCargoProviderRegistry providers;
    private final AirlineDeadLetterService deadLetters;
    private final AirlineIntegrationAttemptService attempts;

    public AirCargoIntegrationController(
            AirCargoProviderRegistry providers,
            AirlineDeadLetterService deadLetters,
            AirlineIntegrationAttemptService attempts) {
        this.providers = providers;
        this.deadLetters = deadLetters;
        this.attempts = attempts;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        AirCargoProviderPort active = providers.active();
        var capabilities = active == null ? null : active.capabilities();
        List<AirCargoProviderPort> searchProviders = providers.searchProviders();
        List<AirCargoProviderRegistry.ProviderHealth> providerHealth = providers.health();

        boolean liveSearchConfigured = !searchProviders.isEmpty();
        boolean liveBookingConfigured = providerHealth.stream()
                .anyMatch(p -> p.configured() && p.scheduleSearch() && p.booking());
        boolean liveTrackingConfigured = providerHealth.stream()
                .anyMatch(p -> p.configured() && p.flightStatus());
        boolean liveCancellationConfigured = providerHealth.stream()
                .anyMatch(p -> p.configured() && p.cancellation());
        boolean liveCapacityConfigured = providerHealth.stream()
                .anyMatch(p -> p.configured() && p.liveCapacity());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selectionMode", providers.selectionMode());
        out.put("provider", active == null ? "NONE" : active.providerCode());
        out.put("activeProvider", active == null ? "NONE" : active.providerCode());
        out.put("mode", liveBookingConfigured ? "EXTERNAL_PROVIDER" : liveSearchConfigured ? "LIVE_AVAILABILITY" : "INTERNAL_AAL");
        out.put("configured", liveBookingConfigured);
        out.put("liveSearchConfigured", liveSearchConfigured);
        out.put("liveSearchProviders", searchProviders.stream().map(AirCargoProviderPort::providerCode).toList());
        out.put("liveAirlineAvailability", liveSearchConfigured);
        out.put("liveCapacity", liveCapacityConfigured);
        out.put("liveBooking", liveBookingConfigured);
        out.put("liveTracking", liveTrackingConfigured);
        out.put("liveCancellation", liveCancellationConfigured);
        out.put("webhooks", providers.health().stream().anyMatch(p -> p.configured() && p.webhooks()));
        out.put("standards", capabilities == null ? List.of() : capabilities.standards());
        out.put("capabilities", capabilities);
        out.put("providers", providerHealth);
        out.put("deadLetters", deadLetters.countOpen());
        out.put("attempts", active == null ? List.of() : attempts.health(active.providerCode()));
        return out;
    }

    @GetMapping("/providers")
    public List<AirCargoProviderRegistry.ProviderHealth> providers() {
        return providers.health();
    }

    /**
     * Tests every configured live search provider without persisting results.
     * This is the operator-level connectivity check when AAL runs in AUTO mode.
     */
    @PostMapping("/verify-all")
    public ResponseEntity<Map<String, Object>> verifyAll(@RequestBody VerifyRequest request) {
        if (request == null || request.origin() == null || request.destination() == null
                || request.weightKg() == null || request.weightKg().signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "tested", false,
                    "error", "origin, destination and positive weight are required"));
        }

        String origin = request.origin().trim().toUpperCase();
        String destination = request.destination().trim().toUpperCase();
        if (!origin.matches("[A-Z]{3}") || !destination.matches("[A-Z]{3}") || origin.equals(destination)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "tested", false,
                    "error", "Origin and destination must be different valid IATA airport codes"));
        }

        Instant from = request.from() == null ? Instant.now() : request.from();
        Instant to = request.to() == null ? from.plus(Duration.ofDays(1)) : request.to();
        if (!to.isAfter(from)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "tested", false,
                    "error", "The requested time window is invalid"));
        }

        List<AirCargoProviderPort> liveProviders = providers.searchProviders();
        if (liveProviders.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "tested", true,
                    "status", "NO_CONFIGURED_PROVIDERS",
                    "providers", List.of()));
        }

        List<Map<String, Object>> results = liveProviders.stream()
                .map(provider -> verifyProvider(provider, origin, destination, from, to, request.weightKg()))
                .toList();

        long connected = results.stream()
                .filter(row -> "CONNECTED".equals(row.get("status"))
                        || "CONNECTED_NO_MATCHES".equals(row.get("status")))
                .count();

        return ResponseEntity.ok(Map.of(
                "tested", true,
                "status", connected == results.size() ? "ALL_PROVIDERS_REACHABLE" : "PARTIAL_PROVIDER_CONNECTIVITY",
                "providerCount", results.size(),
                "reachableProviderCount", connected,
                "providers", results));
    }

    /**
     * Performs an intentional live provider search without writing offers to AAL.
     * This is the authoritative operator test for outbound airline connectivity;
     * /health reports configuration/capabilities only.
     */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody VerifyRequest request) {
        if (request == null || request.providerCode() == null || request.origin() == null || request.destination() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "tested", false,
                    "error", "providerCode, origin and destination are required"));
        }

        AirCargoProviderPort provider = providers.find(request.providerCode())
                .orElse(null);
        if (provider == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "tested", false,
                    "configured", false,
                    "provider", safe(request.providerCode()),
                    "error", "Unknown air-cargo provider"));
        }
        if (!provider.configured() || !provider.capabilities().scheduleSearch()) {
            return ResponseEntity.ok(Map.of(
                    "tested", false,
                    "configured", provider.configured(),
                    "provider", provider.providerCode(),
                    "status", "NOT_CONFIGURED",
                    "configurationIssues", provider.configurationIssues()));
        }

        String origin = request.origin().trim().toUpperCase();
        String destination = request.destination().trim().toUpperCase();
        if (!origin.matches("[A-Z]{3}") || !destination.matches("[A-Z]{3}") || origin.equals(destination)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "tested", false,
                    "provider", provider.providerCode(),
                    "error", "Origin and destination must be different valid IATA airport codes"));
        }

        Instant from = request.from() == null ? Instant.now() : request.from();
        Instant to = request.to() == null ? from.plus(Duration.ofDays(1)) : request.to();
        if (!to.isAfter(from) || request.weightKg() == null || request.weightKg().signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "tested", false,
                    "provider", provider.providerCode(),
                    "error", "A positive weight and valid time window are required"));
        }

        long started = System.nanoTime();
        try {
            List<AirCargoProviderPort.FlightOffer> offers = provider.searchFlights(
                    origin, destination, from, to, request.weightKg());
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            long offerCount = offers == null ? 0 : offers.size();
            long bookable = offers == null ? 0
                    : offers.stream().filter(AirCargoProviderPort.FlightOffer::bookable).count();
            List<String> carriers = offers == null ? List.of() : offers.stream()
                    .map(AirCargoProviderPort.FlightOffer::carrierCode)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            List<String> warnings = List.of();
            String verificationStatus = warnings.isEmpty()
                    ? (offerCount > 0 ? "CONNECTED" : "CONNECTED_NO_MATCHES")
                    : (offerCount > 0 ? "CONNECTED_WITH_WARNINGS" : "DEGRADED");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tested", true);
            response.put("configured", true);
            response.put("provider", provider.providerCode());
            response.put("status", verificationStatus);
            response.put("latencyMs", latencyMs);
            response.put("offerCount", offerCount);
            response.put("bookableOfferCount", bookable);
            response.put("carriers", carriers);
            response.put("checkedFrom", from);
            response.put("checkedTo", to);
            if (!warnings.isEmpty()) {
                response.put("warnings", warnings);
            }
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return ResponseEntity.ok(Map.of(
                    "tested", true,
                    "configured", true,
                    "provider", provider.providerCode(),
                    "status", "DEGRADED",
                    "latencyMs", latencyMs,
                    "offerCount", 0,
                    "bookableOfferCount", 0,
                    "error", safeMessage(ex)));
        }
    }

    private Map<String, Object> verifyProvider(
            AirCargoProviderPort provider,
            String origin,
            String destination,
            Instant from,
            Instant to,
            BigDecimal weightKg) {
        long started = System.nanoTime();
        if (!provider.configured() || !provider.capabilities().scheduleSearch()) {
            return Map.of(
                    "tested", false,
                    "configured", provider.configured(),
                    "provider", provider.providerCode(),
                    "status", "NOT_CONFIGURED",
                    "configurationIssues", provider.configurationIssues());
        }
        try {
            List<AirCargoProviderPort.FlightOffer> offers = provider.searchFlights(origin, destination, from, to, weightKg);
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            long offerCount = offers == null ? 0 : offers.size();
            long bookable = offers == null ? 0 : offers.stream().filter(AirCargoProviderPort.FlightOffer::bookable).count();
            List<String> carriers = offers == null ? List.of() : offers.stream()
                    .map(AirCargoProviderPort.FlightOffer::carrierCode)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
            return Map.of(
                    "tested", true,
                    "configured", true,
                    "provider", provider.providerCode(),
                    "status", offerCount > 0 ? "CONNECTED" : "CONNECTED_NO_MATCHES",
                    "latencyMs", latencyMs,
                    "offerCount", offerCount,
                    "bookableOfferCount", bookable,
                    "carriers", carriers);
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            return Map.of(
                    "tested", true,
                    "configured", true,
                    "provider", provider.providerCode(),
                    "status", "DEGRADED",
                    "latencyMs", latencyMs,
                    "error", safe(ex.getMessage()));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    public record VerifyRequest(
            String providerCode,
            String origin,
            String destination,
            BigDecimal weightKg,
            Instant from,
            Instant to) {}

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    @GetMapping("/track")
    public Map<String, Object> track(
            @RequestParam String providerCode,
            @RequestParam String providerReference) {
        AirCargoProviderPort provider = providers.resolve(providerCode);
        AirCargoProviderPort.FlightStatus status = provider.getFlightStatusByProviderReference(providerReference);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider.providerCode());
        out.put("providerReference", providerReference);
        out.put("status", status);
        return out;
    }

    @GetMapping("/dead-letters")
    public List<Map<String, Object>> deadLetters() {
        return deadLetters.list();
    }

    @PostMapping("/dead-letters/{id}/requeue")
    public Map<String, Object> requeue(@PathVariable UUID id) {
        deadLetters.requeue(id);
        return Map.of("id", id, "status", "OPEN");
    }

    @PostMapping("/dead-letters/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable UUID id) {
        deadLetters.resolve(id);
        return Map.of("id", id, "status", "RESOLVED");
    }
}
