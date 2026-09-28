package com.logiplatform.controller;

import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.integration.CargoAiAirCargoProvider;
import com.logiplatform.service.AirlineDeadLetterService;
import com.logiplatform.service.AirlineIntegrationAttemptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
        var provider = providers.active();
        var capabilities = provider.capabilities();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selectionMode", providers.selectionMode());
        out.put("provider", provider.providerCode());
        out.put("mode", capabilities.booking() ? "EXTERNAL_PROVIDER" : "INTERNAL_AAL");
        out.put("configured", capabilities.booking());
        out.put("liveAirlineAvailability", capabilities.scheduleSearch());
        out.put("liveCapacity", capabilities.liveCapacity());
        out.put("liveBooking", capabilities.booking());
        out.put("liveTracking", capabilities.flightStatus());
        out.put("liveCancellation", capabilities.cancellation());
        out.put("webhooks", capabilities.webhooks());
        out.put("standards", capabilities.standards());
        out.put("capabilities", capabilities);
        out.put("providers", providers.health());
        out.put("deadLetters", deadLetters.list().size());
        out.put("attempts", attempts.health(provider.providerCode()));

        if (provider instanceof CargoAiAirCargoProvider cargoAi) {
            out.put("configurationIssues", cargoAi.configurationIssues());
        }

        return out;
    }

    @GetMapping("/providers")
    public List<AirCargoProviderRegistry.ProviderHealth> providers() {
        return providers.health();
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
