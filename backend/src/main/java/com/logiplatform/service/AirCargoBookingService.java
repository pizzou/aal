package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.AirCargoBooking;
import com.logiplatform.model.AirCargoFlight;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.repository.AirCargoFlightRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.security.core.context.SecurityContextHolder;
import com.logiplatform.security.TenantPrincipal;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.logiplatform.dto.AirCargoDtos.*;

@Service
public class AirCargoBookingService {
    private final AirCargoBookingRepository bookings; private final AirCargoFlightRepository flights; private final ShipmentService shipments; private final AirCargoProviderRegistry providers; private final AirlineIntegrationAttemptService attempts; private final AirlineDeadLetterService deadLetters; private final AuditService audit;
    public AirCargoBookingService(AirCargoBookingRepository b,AirCargoFlightRepository f,ShipmentService s,AirCargoProviderRegistry p,AirlineIntegrationAttemptService a,AirlineDeadLetterService d,AuditService audit){bookings=b;flights=f;shipments=s;providers=p;attempts=a;deadLetters=d;this.audit=audit;}

    @Transactional
    public BookingResponse book(BookRequest r){
        UUID tenant=TenantContext.getTenantId(); validate(r); shipments.get(r.shipmentId()); String idem=cleanKey(r.idempotencyKey());
        AirCargoBooking prior=bookings.findByTenantIdAndIdempotencyKey(tenant,idem).orElse(null); if(prior!=null)return BookingResponse.from(prior);
        AirCargoProviderPort provider=providers.active();
        AirCargoFlight reserved=findAndReserve(tenant,r);
        String source=provider.capabilities().booking()?provider.providerCode():"INTERNAL_CAPACITY";
        AirCargoBooking b=new AirCargoBooking(tenant,r.shipmentId(),r.carrierCode().trim().toUpperCase(),r.carrierName(),r.flightNumber().trim().toUpperCase(),r.departureTime(),r.arrivalTime(),r.originCode().trim().toUpperCase(),r.destinationCode().trim().toUpperCase(),r.weightKg(),"PENDING",source,idem);
        b.setServiceLevel(r.serviceLevel()); b.operation(idem,"BOOK");
        try { bookings.saveAndFlush(b); } catch (DataIntegrityViolationException duplicate) {
            AirCargoBooking existing = bookings.findByTenantIdAndIdempotencyKey(tenant, idem).orElseThrow(() -> duplicate);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return BookingResponse.from(existing);
        }
        if(!provider.capabilities().booking() && reserved==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"No persisted capacity is available for this flight");
        if(!provider.capabilities().booking()){
            String ref="CAP-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(); b.confirm(r.weightKg(),ref,ref,"INTERNAL_CAPACITY_RESERVATION"); BookingResponse response=BookingResponse.from(bookings.save(b));
            audit(response.id(),"AIR_BOOKING_CREATED","CREATE",response.status());
            return response;
        }
        String correlation=UUID.randomUUID().toString(); UUID attempt=attempts.start(provider.providerCode(),"BOOK",idem,correlation,r.toString());
        try{
            AirCargoProviderPort.BookingResult result=provider.book(new AirCargoProviderPort.BookingCommand(idem,r.shipmentId().toString(),r.carrierCode(),r.carrierName(),r.flightNumber(),r.departureTime(),r.arrivalTime(),r.originCode(),r.destinationCode(),r.weightKg(),r.serviceLevel()));
            attempts.success(attempt,200,result.rawResponse());
            String status=result.status()==null?"PENDING":result.status().toUpperCase();
            if("CONFIRMED".equals(status))b.confirm(result.confirmedWeightKg()==null?r.weightKg():result.confirmedWeightKg(),result.providerReference(),result.confirmationNumber(),result.rawResponse());
            else b.pending(result.providerReference(),result.rawResponse());
            BookingResponse response=BookingResponse.from(bookings.save(b));
            audit(response.id(),"AIR_BOOKING_CREATED","CREATE",response.status());
            return response;
        }catch(Exception ex){attempts.failure(attempt,null,ex.getMessage());deadLetters.enqueue(provider.providerCode(),"BOOK",idem,correlation,r.toString(),ex.getMessage());throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Airline booking failed; the request was placed in the integration dead-letter queue");}
    }

    @Transactional
    public BookingResponse amend(UUID id,AmendBookingRequest r){
        UUID tenant=TenantContext.getTenantId(); AirCargoBooking b=owned(id); if("CANCELLED".equals(b.getStatus()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Cancelled booking cannot be amended");
        String key=cleanKey(r.idempotencyKey());
        if (key.equals(b.getLastOperationKey()) && "AMEND".equals(b.getLastProviderOperation())) return BookingResponse.from(b);
        b.operation(key,"AMEND"); AirCargoProviderPort p=providers.active();
        if(!p.capabilities().amendment()) throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,"The configured airline provider does not support booking amendments");
        UUID attempt=attempts.start(p.providerCode(),"AMEND",key,UUID.randomUUID().toString(),r.toString());
        try{AirCargoProviderPort.BookingResult result=p.amend(new AirCargoProviderPort.AmendmentCommand(key,b.getProviderReference(),r.flightNumber(),r.departureTime(),r.arrivalTime(),r.weightKg(),r.serviceLevel()));attempts.success(attempt,200,result.rawResponse());b.amend(r.weightKg(),r.flightNumber(),r.departureTime(),r.arrivalTime(),r.serviceLevel(),result.providerReference(),result.rawResponse());BookingResponse response=BookingResponse.from(bookings.save(b));audit(response.id(),"AIR_BOOKING_AMENDED","AMEND",response.status());return response;}
        catch(Exception ex){attempts.failure(attempt,null,ex.getMessage());deadLetters.enqueue(p.providerCode(),"AMEND",key,attempt.toString(),r.toString(),ex.getMessage());throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Airline amendment failed; request queued for integration review");}
    }

    @Transactional
    public BookingResponse cancel(UUID id,CancelBookingRequest r){
        UUID tenant=TenantContext.getTenantId(); AirCargoBooking b=owned(id); if("CANCELLED".equals(b.getStatus()))return BookingResponse.from(b); String key=cleanKey(r.idempotencyKey()); AirCargoProviderPort p=providers.active();
        if (key.equals(b.getLastOperationKey()) && "CANCEL".equals(b.getLastProviderOperation())) return BookingResponse.from(b);
        b.operation(key,"CANCEL");
        if(p.capabilities().cancellation() && !"INTERNAL_CAPACITY".equals(b.getProvider())){
            UUID attempt=attempts.start(p.providerCode(),"CANCEL",key,UUID.randomUUID().toString(),r.toString());
            try{AirCargoProviderPort.BookingResult result=p.cancel(new AirCargoProviderPort.CancellationCommand(key,b.getProviderReference(),r.reason()));attempts.success(attempt,200,result.rawResponse());b.cancel(r.reason(),result.rawResponse());}
            catch(Exception ex){attempts.failure(attempt,null,ex.getMessage());deadLetters.enqueue(p.providerCode(),"CANCEL",key,attempt.toString(),r.toString(),ex.getMessage());throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Airline cancellation failed; request queued for integration review");}
        }else{b.cancel(r.reason(),"INTERNAL_BOOKING_CANCELLATION");}
        releaseCapacityIfInternal(tenant,b); BookingResponse response=BookingResponse.from(bookings.save(b));audit(response.id(),"AIR_BOOKING_CANCELLED","CANCEL",response.status()); return response;
    }

    @Transactional(readOnly=true) public List<BookingResponse> list(){return bookings.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId()).stream().map(BookingResponse::from).toList();}
    @Transactional(readOnly=true) public BookingResponse get(UUID id){return BookingResponse.from(owned(id));}
    private AirCargoBooking owned(UUID id){return bookings.findByTenantIdAndId(TenantContext.getTenantId(),id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Booking not found"));}
    private AirCargoFlight findAndReserve(UUID tenant,BookRequest r){List<AirCargoFlight> c=flights.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(tenant,r.originCode().trim().toUpperCase(),r.destinationCode().trim().toUpperCase(),r.departureTime().minus(2,ChronoUnit.MINUTES),r.departureTime().plus(2,ChronoUnit.MINUTES));if(c.isEmpty())return null;AirCargoFlight f=flights.findByTenantIdAndIdForUpdate(tenant,c.get(0).getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Flight disappeared during booking"));if(!f.reserve(r.weightKg()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Insufficient live capacity");flights.save(f);return f;}
    private void releaseCapacityIfInternal(UUID tenant,AirCargoBooking b){if(!"INTERNAL_CAPACITY".equals(b.getProvider()))return;List<AirCargoFlight> c=flights.findAllByTenantIdAndOriginCodeAndDestinationCodeAndDepartureTimeBetweenOrderByDepartureTimeAsc(tenant,b.getOriginCode(),b.getDestinationCode(),b.getDepartureTime().minus(2,ChronoUnit.MINUTES),b.getDepartureTime().plus(2,ChronoUnit.MINUTES));if(!c.isEmpty()){AirCargoFlight f=flights.findByTenantIdAndIdForUpdate(tenant,c.get(0).getId()).orElse(null);if(f!=null){f.release(b.getRequestedWeightKg());flights.save(f);}}}
    private void audit(UUID bookingId,String action,String operation,String status){
        UUID tenant=TenantContext.getTenantId(); UUID user=null; try{Object p=SecurityContextHolder.getContext().getAuthentication().getPrincipal();if(p instanceof TenantPrincipal tp)user=tp.userId();}catch(Exception ignored){}
        audit.record(tenant,user,action,"AIR_CARGO_BOOKING",bookingId,operation,"/api/air-cargo/bookings",null,null,200,true,null,status,null);
    }

    private static void validate(BookRequest r){if(r.departureTime().isBefore(Instant.now().minusSeconds(60)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cannot book a flight that has already departed");if(r.arrivalTime()!=null&&!r.arrivalTime().isAfter(r.departureTime()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Arrival must be after departure");if(r.originCode().equalsIgnoreCase(r.destinationCode()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Origin and destination must differ");}
    private static String cleanKey(String key){String x=key==null?"":key.trim();if(x.isBlank()||x.length()>255)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid idempotency key is required");return x;}
}
