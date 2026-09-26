package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.service.control.BookingStateMachineService;
import com.logiplatform.service.control.ReconciliationTaskService;
import com.logiplatform.service.control.DistributedJobLockService;
import java.time.Duration;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AirlineBookingReconciliationService {
    private final AirCargoBookingRepository bookings; private final AirCargoProviderRegistry providers; private final AirlineIntegrationAttemptService attempts; private final AirlineDeadLetterService deadLetters; private final BookingStateMachineService states; private final ReconciliationTaskService tasks; private final DistributedJobLockService jobLocks; private final UUID tenantId; private final boolean enabled;
    public AirlineBookingReconciliationService(AirCargoBookingRepository bookings,AirCargoProviderRegistry providers,AirlineIntegrationAttemptService attempts,AirlineDeadLetterService deadLetters,BookingStateMachineService states,ReconciliationTaskService tasks,DistributedJobLockService jobLocks,@Value("${app.single-tenant.id}") String tenant,@Value("${aircargo.reconciliation.enabled:false}") boolean enabled){this.bookings=bookings;this.providers=providers;this.attempts=attempts;this.deadLetters=deadLetters;this.states=states;this.tasks=tasks;this.jobLocks=jobLocks;this.tenantId=UUID.fromString(tenant);this.enabled=enabled;}
    @Scheduled(fixedDelayString="${aircargo.reconciliation.interval-ms:300000}")
    public void scheduled(){if(!enabled)return;TenantContext.setTenantId(tenantId); String owner=jobLocks.tryAcquire("airline-booking-reconciliation",Duration.ofMinutes(5)); if(owner==null){TenantContext.clear();return;} try{reconcile();}finally{jobLocks.release("airline-booking-reconciliation",owner);TenantContext.clear();}}

    @Transactional
    public int reconcile(){
        var p=providers.active(); if(!p.capabilities().booking()) return 0; int count=0;
        List<AirCargoBooking> rows=bookings.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
        for(AirCargoBooking b:rows){
            if("INTERNAL_CAPACITY".equals(b.getProvider())||"CANCELLED".equals(b.getStatus())||(!"UNKNOWN".equals(b.getStatus())&& !"RECONCILING".equals(b.getStatus()))) continue;
            String ref=b.getProviderReference();
            try{
                states.transition(b,"RECONCILING","PROVIDER_OUTCOME_LOOKUP",UUID.randomUUID().toString());
                var r=ref==null||ref.isBlank()?null:p.getBooking(ref);
                if(r==null){tasks.enqueue(p.providerCode(),"BOOK","AIR_CARGO_BOOKING",b.getId(),b.getIdempotencyKey(),ref,"Provider does not support lookup without external reference");continue;}
                String status=r.status()==null?"UNKNOWN":r.status().toUpperCase(Locale.ROOT);
                if("CONFIRMED".equals(status)){b.confirm(r.confirmedWeightKg()==null?b.getRequestedWeightKg():r.confirmedWeightKg(),r.providerReference(),r.confirmationNumber(),r.rawResponse());states.transition(b,"CONFIRMED","PROVIDER_RECONCILIATION_CONFIRMED",UUID.randomUUID().toString());}
                else if("CANCELLED".equals(status)){b.cancel("PROVIDER_RECONCILIATION",r.rawResponse());states.transition(b,"CANCELLED","PROVIDER_RECONCILIATION_CANCELLED",UUID.randomUUID().toString());}
                else if("FAILED".equals(status)){b.fail(r.rawResponse());states.transition(b,"FAILED","PROVIDER_RECONCILIATION_FAILED",UUID.randomUUID().toString());}
                else {states.transition(b,"UNKNOWN","PROVIDER_STILL_UNKNOWN",UUID.randomUUID().toString());}
                bookings.save(b); count++;
            }catch(Exception ex){
                try{states.transition(b,"UNKNOWN","RECONCILIATION_ERROR",UUID.randomUUID().toString());bookings.save(b);}catch(Exception ignored){}
                tasks.enqueue(p.providerCode(),"BOOK","AIR_CARGO_BOOKING",b.getId(),b.getIdempotencyKey(),ref,ex.getMessage());
                deadLetters.enqueue(p.providerCode(),"RECONCILE_BOOKING",b.getIdempotencyKey(),UUID.randomUUID().toString(),ref,ex.getMessage());
            }
        }
        return count;
    }
}
