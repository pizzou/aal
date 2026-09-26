package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentEtaHistory;
import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.TrackingEventType;
import com.logiplatform.repository.ShipmentEtaHistoryRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ShipmentEtaTrackingService {
    private final ShipmentRepository shipments;
    private final ShipmentEtaHistoryRepository history;
    private final ShipmentService shipmentService; private final NotificationService notifications; private final String operationsEmail;

    public ShipmentEtaTrackingService(ShipmentRepository shipments,ShipmentEtaHistoryRepository history,ShipmentService shipmentService,NotificationService notifications,@Value("${aircargo.operations-notification-email:}") String operationsEmail){this.shipments=shipments;this.history=history;this.shipmentService=shipmentService;this.notifications=notifications;this.operationsEmail=operationsEmail==null?"":operationsEmail.trim();}

    @Transactional
    public AirCargoProviderPort.FlightStatus apply(UUID shipmentId, AirCargoProviderPort.FlightStatus status, String source){
        UUID tenant=TenantContext.getTenantId();
        Shipment s=shipments.findByIdAndTenantId(shipmentId,tenant).orElseThrow(()->new IllegalArgumentException("Shipment not found"));
        Instant newEtd=first(status.actualDeparture(),status.estimatedDeparture(),status.scheduledDeparture());
        Instant newEta=first(status.actualArrival(),status.estimatedArrival(),status.scheduledArrival());
        Instant oldEtd=s.getEtd(), oldEta=s.getEta();
        boolean changed=!Objects.equals(oldEtd,newEtd)||!Objects.equals(oldEta,newEta);
        if(changed){
            boolean departed = status.actualDeparture() != null && s.getActualDeparture() == null;
            boolean arrived = status.actualArrival() != null && s.getActualArrival() == null;
            s.updateFlightTracking(newEtd,newEta,status.actualDeparture(),status.actualArrival(),status.flightStatus());
            shipments.save(s);
            if (departed) shipmentService.addTrackingEvent(shipmentId,new com.logiplatform.dto.ShipmentDtos.AddTrackingEventRequest("DEPARTED_ORIGIN",null,"Actual flight departure received from "+source, status.actualDeparture()));
            if (arrived) shipmentService.addTrackingEvent(shipmentId,new com.logiplatform.dto.ShipmentDtos.AddTrackingEventRequest("ARRIVED_DESTINATION",null,"Actual flight arrival received from "+source, status.actualArrival()));
            history.save(new ShipmentEtaHistory(tenant,shipmentId,source,status.providerEventId(),s.getFlightNumber(),status.flightStatus(),oldEtd,newEtd,oldEta,newEta,reason(status,oldEtd,oldEta),AirlineIntegrationAttemptService.hash(status.rawResponse())));
            String subject="Shipment ETA updated: "+s.getReferenceCode();
            String body="Shipment "+s.getReferenceCode()+" flight "+s.getFlightNumber()+" received a provider ETA update. New ETA: "+String.valueOf(newEta)+". Source: "+source+".";
            notifications.notify(shipmentId,s.getNotificationEmail(),subject,body);
            if(!operationsEmail.isBlank()) notifications.notify(shipmentId,operationsEmail,subject,body);
        }
        String fs=status.flightStatus()==null?"":status.flightStatus().toLowerCase(Locale.ROOT);
        if(fs.contains("cancel")){
            if(s.getStatus()!=ShipmentStatus.CANCELLED){s.updateStatus(ShipmentStatus.CANCELLED);shipments.save(s);}
            shipmentService.addTrackingEvent(shipmentId,new com.logiplatform.dto.ShipmentDtos.AddTrackingEventRequest("EXCEPTION",null,"Flight cancellation reported by "+source+" for "+s.getFlightNumber(),Instant.now()));
        } else if(fs.contains("divert")){
            shipmentService.addTrackingEvent(shipmentId,new com.logiplatform.dto.ShipmentDtos.AddTrackingEventRequest("EXCEPTION",null,"Flight diversion reported by "+source+" for "+s.getFlightNumber(),Instant.now()));
        } else if(fs.contains("missed")){
            shipmentService.addTrackingEvent(shipmentId,new com.logiplatform.dto.ShipmentDtos.AddTrackingEventRequest("EXCEPTION",null,"Missed connection reported by "+source+" for "+s.getFlightNumber(),Instant.now()));
        } else if(status.departureDelayMinutes()>=60||status.arrivalDelayMinutes()>=60){
            shipmentService.addTrackingEvent(shipmentId,new com.logiplatform.dto.ShipmentDtos.AddTrackingEventRequest("EXCEPTION",null,"Flight delay: departure +"+status.departureDelayMinutes()+"min, arrival +"+status.arrivalDelayMinutes()+"min",Instant.now()));
        }
        return status;
    }
    @Transactional(readOnly=true)
    public List<ShipmentEtaHistory> history(UUID shipmentId){shipments.findByIdAndTenantId(shipmentId,TenantContext.getTenantId()).orElseThrow(()->new IllegalArgumentException("Shipment not found"));return history.findAllByTenantIdAndShipmentIdOrderByObservedAtDesc(TenantContext.getTenantId(),shipmentId);}
    private static Instant first(Instant... x){for(Instant i:x)if(i!=null)return i;return null;}
    private static String reason(AirCargoProviderPort.FlightStatus s,Instant oldEtd,Instant oldEta){if(s.flightStatus()!=null&&s.flightStatus().toLowerCase(Locale.ROOT).contains("cancel"))return "CANCELLATION";if(s.flightStatus()!=null&&s.flightStatus().toLowerCase(Locale.ROOT).contains("divert"))return "DIVERSION";if(s.arrivalDelayMinutes()>=60||s.departureDelayMinutes()>=60)return "DELAY";return oldEtd==null&&oldEta==null?"INITIAL_PROVIDER_SYNC":"SCHEDULE_UPDATE";}
}
