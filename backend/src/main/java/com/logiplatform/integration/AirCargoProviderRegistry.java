package com.logiplatform.integration;

import org.springframework.stereotype.Service;

@Service
public class AirCargoProviderRegistry {
    private final GenericHttpAirCargoProvider generic;
    public AirCargoProviderRegistry(GenericHttpAirCargoProvider generic) { this.generic = generic; }
    public AirCargoProviderPort active() { return generic; }
}
