package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AirlineBookingReconciliationService {
    private final AirCargoBookingRepository bookings; private final AirCargoProviderRegistry providers; private final AirlineIntegrationAttemptService attempts; private final AirlineDeadLetterService deadLetters; private final UUID tenantId; private final boolean enabled;
    public AirlineBookingReconciliationService(AirCargoBookingRepository bookings,AirCargoProviderRegistry providers,AirlineIntegrationAttemptService attempts,AirlineDeadLetterService deadLetters,@Value("${app.single-tenant.id}") String tenant,@Value("${aircargo.reconciliation.enabled:false}") boolean enabled){this.bookings=bookings;this.providers=providers;this.attempts=attempts;this.deadLetters=deadLetters;this.tenantId=UUID.fromString(tenant);this.enabled=enabled;}
    @Scheduled(fixedDelayString="${aircargo.reconciliation.interval-ms:300000}")
    public void scheduled(){if(!enabled)return;TenantContext.setTenantId(tenantId);try{reconcile();}finally{TenantContext.clear();}}
    @Transactional public int reconcile(){var p=providers.active();if(!p.capabilities().booking())return 0;int count=0;List<AirCargoBooking> rows=bookings.findAllByTenantIdOrderByCreatedAtDesc(tenantId);for(AirCargoBooking b:rows){if("INTERNAL_CAPACITY".equals(b.getProvider())||b.getProviderReference()==null||b.getProviderReference().isBlank()||"CANCELLED".equals(b.getStatus()))continue;try{var r=p.getBooking(b.getProviderReference());if(r==null)continue;String status=r.status()==null?b.getStatus():r.status().toUpperCase(Locale.ROOT);if("CONFIRMED".equals(status)&&!"CONFIRMED".equals(b.getStatus()))b.confirm(r.confirmedWeightKg()==null?b.getRequestedWeightKg():r.confirmedWeightKg(),r.providerReference(),r.confirmationNumber(),r.rawResponse());else if("CANCELLED".equals(status)&&!"CANCELLED".equals(b.getStatus()))b.cancel("PROVIDER_RECONCILIATION",r.rawResponse());else if("FAILED".equals(status))b.fail(r.rawResponse());bookings.save(b);count++;}catch(Exception ex){deadLetters.enqueue(p.providerCode(),"RECONCILE_BOOKING",b.getIdempotencyKey(),UUID.randomUUID().toString(),b.getProviderReference(),ex.getMessage());}}return count;}
}
