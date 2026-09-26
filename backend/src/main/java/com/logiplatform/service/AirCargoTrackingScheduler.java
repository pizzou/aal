package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.TransportMode;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class AirCargoTrackingScheduler {
    private final ShipmentRepository shipments; private final AirCargoProviderRegistry registry; private final ShipmentEtaTrackingService eta; private final UUID tenantId; private final boolean enabled;
    public AirCargoTrackingScheduler(ShipmentRepository shipments,AirCargoProviderRegistry registry,ShipmentEtaTrackingService eta,@Value("${app.single-tenant.id}") String tenant,@Value("${aircargo.eta-poll.enabled:false}") boolean enabled){this.shipments=shipments;this.registry=registry;this.eta=eta;this.tenantId=UUID.fromString(tenant);this.enabled=enabled;}
    @Scheduled(fixedDelayString="${aircargo.eta-poll.interval-ms:300000}")
    public void poll(){
        if(!enabled)return;
        AirCargoProviderPort p=registry.active();
        if(!p.capabilities().flightStatus())return;
        TenantContext.setTenantId(tenantId);
        try {
            Set<UUID> ids=new LinkedHashSet<>();
            for(ShipmentStatus st:List.of(ShipmentStatus.BOOKED,ShipmentStatus.IN_TRANSIT,ShipmentStatus.PLANNING)){
                for(Shipment s:shipments.findAllByTenantIdAndWeightKgIsNotNullAndStatus(tenantId,st))
                    if(s.getTransportMode()==TransportMode.AIR&&s.getFlightNumber()!=null&&!s.getFlightNumber().isBlank()) ids.add(s.getId());
            }
            for(UUID id:ids){
                try {
                    Shipment s=shipments.findByIdAndTenantId(id,tenantId).orElse(null);
                    if(s==null)continue;
                    eta.apply(id,p.getFlightStatus(s.getFlightNumber(),LocalDate.now().toString()),p.providerCode());
                } catch(Exception ignored) {}
            }
        } finally {
            TenantContext.clear();
        }
    }
}
