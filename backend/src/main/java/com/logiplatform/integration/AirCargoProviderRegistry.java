package com.logiplatform.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Selects the best configured air-cargo adapter.
 *
 * <p>AAL uses AUTO as the production default. When AUTO is selected, a
 * configured real provider is preferred over the generic adapter. CargoAi is
 * preferred because it supplies a concrete search/quote/book/track workflow;
 * the generic HTTP adapter remains available as a partner-specific fallback.</p>
 */
@Service
public class AirCargoProviderRegistry {
    public record ProviderHealth(
            String code,
            boolean configured,
            boolean active,
            boolean scheduleSearch,
            boolean liveCapacity,
            boolean booking,
            boolean amendment,
            boolean cancellation,
            boolean flightStatus,
            boolean awbSubmission,
            boolean webhooks,
            boolean oauth2,
            boolean apiKey,
            List<String> standards,
            String status) {
    }

    private final GenericHttpAirCargoProvider generic;
    private final Map<String, AirCargoProviderPort> adapters;
    private final String configuredCode;

    public AirCargoProviderRegistry(
            GenericHttpAirCargoProvider generic,
            List<AirCargoProviderPort> providers,
            @Value("${aircargo.provider.code:AUTO}") String configuredCode) {
        this.generic = generic;
        this.configuredCode = configuredCode == null || configuredCode.isBlank()
                ? "AUTO"
                : configuredCode.trim().toUpperCase(Locale.ROOT);

        Map<String, AirCargoProviderPort> discovered = new LinkedHashMap<>();
        for (AirCargoProviderPort provider : providers) {
            discovered.put(
                    provider.providerCode().trim().toUpperCase(Locale.ROOT),
                    provider);
        }
        discovered.putIfAbsent("GENERIC_HTTP", generic);
        this.adapters = Map.copyOf(discovered);
    }

    public AirCargoProviderPort active() {
        if (!"AUTO".equals(configuredCode)) {
            return adapters.getOrDefault(configuredCode, generic);
        }

        // Prefer a provider that can execute the complete live air workflow.
        AirCargoProviderPort cargoAi = adapters.get("CARGOAI");
        if (isLiveBookProvider(cargoAi)) {
            return cargoAi;
        }

        // Then prefer any other concrete provider with search and booking.
        for (AirCargoProviderPort provider : adapters.values()) {
            if (provider == generic) {
                continue;
            }
            if (isLiveBookProvider(provider)) {
                return provider;
            }
        }

        // Finally use a configured generic HTTP adapter if available.
        if (isConfigured(generic)) {
            return generic;
        }

        return generic;
    }

    public Optional<AirCargoProviderPort> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                adapters.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public List<AirCargoProviderPort.ProviderCapabilities> capabilities() {
        return adapters.values().stream()
                .map(AirCargoProviderPort::capabilities)
                .toList();
    }

    public Set<String> codes() {
        return adapters.keySet();
    }

    public String selectionMode() {
        return configuredCode;
    }

    public List<ProviderHealth> health() {
        String activeCode = active().providerCode();
        List<ProviderHealth> result = new ArrayList<>();

        for (Map.Entry<String, AirCargoProviderPort> entry : adapters.entrySet()) {
            AirCargoProviderPort provider = entry.getValue();
            AirCargoProviderPort.ProviderCapabilities c = provider.capabilities();
            boolean configured = isConfigured(provider);
            String status;

            if (provider.providerCode().equalsIgnoreCase(activeCode)) {
                status = configured ? "ACTIVE" : "ACTIVE_FALLBACK";
            } else if (configured) {
                status = "AVAILABLE";
            } else {
                status = "NOT_CONFIGURED";
            }

            result.add(new ProviderHealth(
                    provider.providerCode(),
                    configured,
                    provider.providerCode().equalsIgnoreCase(activeCode),
                    c.scheduleSearch(),
                    c.liveCapacity(),
                    c.booking(),
                    c.amendment(),
                    c.cancellation(),
                    c.flightStatus(),
                    c.awbSubmission(),
                    c.webhooks(),
                    c.oauth2(),
                    c.apiKey(),
                    c.standards(),
                    status));
        }

        return result;
    }

    private boolean isLiveBookProvider(AirCargoProviderPort provider) {
        if (provider == null) {
            return false;
        }
        AirCargoProviderPort.ProviderCapabilities c = provider.capabilities();
        return c.scheduleSearch() && c.booking();
    }

    private boolean isConfigured(AirCargoProviderPort provider) {
        if (provider == null) {
            return false;
        }
        AirCargoProviderPort.ProviderCapabilities c = provider.capabilities();
        return c.scheduleSearch()
                || c.liveCapacity()
                || c.booking()
                || c.amendment()
                || c.cancellation()
                || c.flightStatus()
                || c.awbSubmission();
    }
}
