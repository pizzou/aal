package com.logiplatform.integration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AirCargoProviderRegistrySafetyTest {

    @Test
    void explicitUnknownProviderFailsClosedInsteadOfSilentlyUsingGeneric() {
        GenericHttpAirCargoProvider generic = mock(GenericHttpAirCargoProvider.class);
        when(generic.providerCode()).thenReturn("GENERIC_HTTP");
        when(generic.configured()).thenReturn(true);
        AirCargoProviderRegistry registry = new AirCargoProviderRegistry(generic, List.of(), "NOT_A_PROVIDER");

        assertThrows(IllegalStateException.class, registry::active);
    }

    @Test
    void explicitUnconfiguredProviderFailsClosed() {
        GenericHttpAirCargoProvider generic = mock(GenericHttpAirCargoProvider.class);
        when(generic.providerCode()).thenReturn("GENERIC_HTTP");
        AirCargoProviderPort provider = mock(AirCargoProviderPort.class);
        when(provider.providerCode()).thenReturn("CARGOAI");
        when(provider.configured()).thenReturn(false);
        when(provider.configurationIssues()).thenReturn(List.of("API key is missing"));

        AirCargoProviderRegistry registry = new AirCargoProviderRegistry(generic, List.of(provider), "CARGOAI");

        assertThrows(IllegalStateException.class, registry::active);
    }

    @Test
    void explicitlySelectedConfiguredProviderIsReturnedWithoutFallback() {
        GenericHttpAirCargoProvider generic = mock(GenericHttpAirCargoProvider.class);
        when(generic.providerCode()).thenReturn("GENERIC_HTTP");
        AirCargoProviderPort provider = mock(AirCargoProviderPort.class);
        when(provider.providerCode()).thenReturn("CARGOAI");
        when(provider.configured()).thenReturn(true);

        AirCargoProviderRegistry registry = new AirCargoProviderRegistry(generic, List.of(provider), "CARGOAI");

        assertSame(provider, registry.active());
    }
}
