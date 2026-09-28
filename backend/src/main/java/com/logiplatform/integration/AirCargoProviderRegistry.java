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
 * Central registry for air-cargo providers.
 *
 * <p>AAL uses AUTO in production so a configured live airline/provider adapter
 * can participate without changing shipment or quotation code. Search may fan
 * out to more than one provider, while booking/tracking/cancellation are routed
 * back to the provider that supplied the selected offer or booking.</p>
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
            String status,
            List<String> configurationIssues) {
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
        if (providers != null) {
            for (AirCargoProviderPort provider : providers) {
                if (provider == null || provider.providerCode() == null || provider.providerCode().isBlank()) {
                    continue;
                }
                discovered.put(
                        provider.providerCode().trim().toUpperCase(Locale.ROOT),
                        provider);
            }
        }
        discovered.putIfAbsent("GENERIC_HTTP", generic);
        this.adapters = Map.copyOf(discovered);
    }

    /** Provider used when an operation does not carry an explicit provider. */
    public AirCargoProviderPort active() {
        if (!"AUTO".equals(configuredCode)) {
            return adapters.getOrDefault(configuredCode, generic);
        }

        // Prefer a configured provider that supports both live search and booking.
        AirCargoProviderPort cargoAi = adapters.get("CARGOAI");
        if (isConfiguredBookProvider(cargoAi)) {
            return cargoAi;
        }

        for (AirCargoProviderPort provider : adapters.values()) {
            if (provider == generic || "CARGOAI".equalsIgnoreCase(provider.providerCode())) {
                continue;
            }
            if (isConfiguredBookProvider(provider)) {
                return provider;
            }
        }

        // A live search-only adapter (for example direct Qatar Availability)
        // should still be visible to users and used for search, but it cannot be
        // silently selected for a booking operation.
        for (AirCargoProviderPort provider : adapters.values()) {
            if (provider == generic) {
                continue;
            }
            if (isConfiguredSearchProvider(provider)) {
                return provider;
            }
        }

        if (generic != null && generic.configured()) {
            return generic;
        }
        return generic;
    }

    /** All currently configured provider adapters capable of live search. */
    public List<AirCargoProviderPort> searchProviders() {
        List<AirCargoProviderPort> result = new ArrayList<>();
        if (!"AUTO".equals(configuredCode)) {
            AirCargoProviderPort selected = adapters.get(configuredCode);
            if (isConfiguredSearchProvider(selected)) {
                result.add(selected);
            }
            return result;
        }

        for (AirCargoProviderPort provider : adapters.values()) {
            if (provider == generic) {
                continue;
            }
            if (isConfiguredSearchProvider(provider)) {
                result.add(provider);
            }
        }
        result.sort((a, b) -> {
            // Keep CargoAi first as the broad aggregation source, then sort
            // remaining direct airline adapters deterministically.
            if ("CARGOAI".equalsIgnoreCase(a.providerCode())) return -1;
            if ("CARGOAI".equalsIgnoreCase(b.providerCode())) return 1;
            return a.providerCode().compareToIgnoreCase(b.providerCode());
        });
        return result;
    }

    /** Resolve a provider by the code persisted on an offer/booking. */
    public AirCargoProviderPort resolve(String code) {
        if (code == null || code.isBlank()) {
            return active();
        }
        AirCargoProviderPort provider = adapters.get(code.trim().toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalArgumentException("Unknown air-cargo provider: " + code);
        }
        if (!provider.configured() && !"GENERIC_HTTP".equalsIgnoreCase(provider.providerCode())) {
            throw new IllegalStateException("Air-cargo provider is not configured: " + provider.providerCode());
        }
        return provider;
    }

    public Optional<AirCargoProviderPort> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public List<AirCargoProviderPort.ProviderCapabilities> capabilities() {
        return adapters.values().stream().map(AirCargoProviderPort::capabilities).toList();
    }

    public Set<String> codes() {
        return adapters.keySet();
    }

    public String selectionMode() {
        return configuredCode;
    }

    public List<ProviderHealth> health() {
        String activeCode = active() == null ? "" : active().providerCode();
        List<ProviderHealth> result = new ArrayList<>();

        for (Map.Entry<String, AirCargoProviderPort> entry : adapters.entrySet()) {
            AirCargoProviderPort provider = entry.getValue();
            AirCargoProviderPort.ProviderCapabilities c = provider.capabilities();
            boolean configured = provider.configured();
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
                    status,
                    provider.configurationIssues()));
        }

        return result;
    }

    private boolean isConfiguredBookProvider(AirCargoProviderPort provider) {
        return provider != null
                && provider.configured()
                && provider.capabilities().scheduleSearch()
                && provider.capabilities().booking();
    }

    private boolean isConfiguredSearchProvider(AirCargoProviderPort provider) {
        return provider != null
                && provider.configured()
                && provider.capabilities().scheduleSearch();
    }
}
