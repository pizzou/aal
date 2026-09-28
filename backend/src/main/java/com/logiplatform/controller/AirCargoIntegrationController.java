package com.logiplatform.controller;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.service.AirlineDeadLetterService;
import com.logiplatform.service.AirlineIntegrationAttemptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

        boolean liveSearchConfigured = !searchProviders.isEmpty();
        boolean liveBookingConfigured = providers.health().stream()
                .anyMatch(p -> p.configured() && p.scheduleSearch() && p.booking());
        boolean liveTrackingConfigured = providers.health().stream()
                .anyMatch(p -> p.configured() && p.flightStatus());
        boolean liveCancellationConfigured = providers.health().stream()
                .anyMatch(p -> p.configured() && p.cancellation());
        boolean liveCapacityConfigured = providers.health().stream()
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
        out.put("providers", providers.health());
        out.put("deadLetters", deadLetters.list().size());
        out.put("attempts", active == null ? List.of() : attempts.health(active.providerCode()));
        return out;
    }

    @GetMapping("/providers")
    public List<AirCargoProviderRegistry.ProviderHealth> providers() {
        return providers.health();
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
