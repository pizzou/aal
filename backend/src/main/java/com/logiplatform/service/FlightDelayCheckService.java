package com.logiplatform.service;

import com.logiplatform.dto.ShipmentDtos;
import com.logiplatform.integration.AirCargoProviderPort;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

@Service
public class FlightDelayCheckService {
    private final FlightStatusPort flightStatusPort; private final ShipmentService shipmentService; private final ShipmentRepository shipmentRepository; private final ShipmentEtaTrackingService etaTracking; private final AirCargoProviderRegistry providers; private final com.logiplatform.repository.AirCargoBookingRepository bookings;
    public FlightDelayCheckService(FlightStatusPort flightStatusPort,ShipmentService shipmentService,ShipmentRepository shipmentRepository,ShipmentEtaTrackingService etaTracking,AirCargoProviderRegistry providers,com.logiplatform.repository.AirCargoBookingRepository bookings){this.flightStatusPort=flightStatusPort;this.shipmentService=shipmentService;this.shipmentRepository=shipmentRepository;this.etaTracking=etaTracking;this.providers=providers;this.bookings=bookings;}
    public FlightStatusPort.FlightStatusResult checkAndFlagDelay(UUID shipmentId){
        Shipment s=shipmentRepository.findByIdAndTenantId(shipmentId,TenantContext.getTenantId()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipment not found"));
        if(s.getFlightNumber()==null||s.getFlightNumber().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This shipment has no flight number set");
        Optional<FlightStatusPort.FlightStatusResult> result=flightStatusPort.getStatus(s.getFlightNumber(), LocalDate.now().toString());
        if(result.isEmpty()){
            AirCargoProviderPort p=providers.active();
            if(p.capabilities().flightStatus()){
                AirCargoProviderPort.FlightStatus x=p.getFlightStatus(s.getFlightNumber(),LocalDate.now().toString());
                etaTracking.apply(shipmentId,x,p.providerCode());
                return new FlightStatusPort.FlightStatusResult(x.flightStatus(),x.departureDelayMinutes(),x.arrivalDelayMinutes(),x.departureDelayMinutes()>=60||x.arrivalDelayMinutes()>=60,x.scheduledDeparture(),x.estimatedDeparture(),x.actualDeparture(),x.scheduledArrival(),x.estimatedArrival(),x.actualArrival(),x.providerEventId(),x.rawResponse());
            }

            // No live provider is configured. Use the shipment's own schedule first,
            // then a confirmed/requested air booking as the operational baseline.
            // This keeps ETA visible and auditable without pretending that a local
            // schedule is a live airline feed.
            var booking = bookings.findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(
                    TenantContext.getTenantId(), shipmentId).stream().findFirst().orElse(null);
            java.time.Instant scheduledDeparture = s.getEtd();
            java.time.Instant scheduledArrival = s.getEta();
            if (booking != null) {
                if (scheduledDeparture == null) scheduledDeparture = booking.getDepartureTime();
                if (scheduledArrival == null) scheduledArrival = booking.getArrivalTime();
            }
            if (scheduledDeparture == null && scheduledArrival == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No live flight-status provider is configured and this shipment has no ETD/ETA schedule");
            }
            AirCargoProviderPort.FlightStatus local = new AirCargoProviderPort.FlightStatus(
                    booking != null ? booking.getStatus() : "SCHEDULED",
                    scheduledDeparture, scheduledDeparture, s.getActualDeparture(),
                    scheduledArrival, scheduledArrival, s.getActualArrival(),
                    0, 0, "LOCAL-" + shipmentId, "{\"source\":\"AAL_SCHEDULE\"}");
            etaTracking.apply(shipmentId, local, "AAL_SCHEDULE");
            return new FlightStatusPort.FlightStatusResult(
                    local.flightStatus(),0,0,false,local.scheduledDeparture(),local.estimatedDeparture(),
                    local.actualDeparture(),local.scheduledArrival(),local.estimatedArrival(),
                    local.actualArrival(),local.providerEventId(),local.rawResponse());
        }
        FlightStatusPort.FlightStatusResult status=result.get();
        etaTracking.apply(shipmentId,new AirCargoProviderPort.FlightStatus(status.flightStatus(),status.scheduledDeparture(),status.estimatedDeparture(),status.actualDeparture(),status.scheduledArrival(),status.estimatedArrival(),status.actualArrival(),status.departureDelayMinutes(),status.arrivalDelayMinutes(),status.providerEventId(),status.rawResponse()),"FLIGHT_STATUS_PROVIDER");
        return status;
    }
}
