package com.logiplatform.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/** Selects a real provider-specific adapter when one is supplied; otherwise Generic HTTP is the fallback. */
@Service
public class AirCargoProviderRegistry {
    private final GenericHttpAirCargoProvider generic;
    private final Map<String,AirCargoProviderPort> adapters;
    private final String configuredCode;

    public AirCargoProviderRegistry(GenericHttpAirCargoProvider generic,
                                    List<AirCargoProviderPort> providers,
                                    @Value("${aircargo.provider.code:GENERIC_HTTP}") String configuredCode) {
        this.generic=generic;
        this.configuredCode=configuredCode==null||configuredCode.isBlank()?"GENERIC_HTTP":configuredCode.trim().toUpperCase(Locale.ROOT);
        Map<String,AirCargoProviderPort> m=new LinkedHashMap<>();
        for(AirCargoProviderPort p:providers) m.put(p.providerCode().toUpperCase(Locale.ROOT),p);
        this.adapters=Map.copyOf(m);
    }

    public AirCargoProviderPort active(){ return adapters.getOrDefault(configuredCode,generic); }
    public Optional<AirCargoProviderPort> find(String code){return Optional.ofNullable(adapters.get(code==null?"":code.trim().toUpperCase(Locale.ROOT)));}
    public List<AirCargoProviderPort.ProviderCapabilities> capabilities(){return adapters.values().stream().map(AirCargoProviderPort::capabilities).toList();}
    public Set<String> codes(){return adapters.keySet();}
}
