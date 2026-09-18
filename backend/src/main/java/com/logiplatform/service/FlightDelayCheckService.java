package com.logiplatform.service;

import com.logiplatform.dto.ShipmentDtos;


import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately thin: this doesn't duplicate the EXCEPTION/notification machinery
 * already built and tested in ShipmentService — it calls the SAME
 * addTrackingEvent(EXCEPTION) path a manually-reported problem uses, so a
 * flight-detected delay shows up in the same tracking timeline and triggers the
 * same notification a human-reported exception would (see NotificationTest for
 * that behavior's coverage). This is the payoff of having built that pipeline
 * properly earlier — a genuinely new data source (real flight status) plugs into
 * existing, already-verified behavior instead of needing its own parallel system.
 */
@Service
public class FlightDelayCheckService {

    private final FlightStatusPort flightStatusPort;
    private final ShipmentService shipmentService;

    public FlightDelayCheckService(FlightStatusPort flightStatusPort, ShipmentService shipmentService) {
        this.flightStatusPort = flightStatusPort;
        this.shipmentService = shipmentService;
    }

    public FlightStatusPort.FlightStatusResult checkAndFlagDelay(UUID shipmentId) {
        ShipmentDtos.ShipmentResponse shipment = shipmentService.get(shipmentId);

        if (shipment.flightNumber() == null || shipment.flightNumber().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This shipment has no flight number set — nothing to check status for");
        }

        Optional<FlightStatusPort.FlightStatusResult> result =
                flightStatusPort.getStatus(shipment.flightNumber(), LocalDate.now().toString());

        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Flight status is not available right now — either no provider is configured "
                    + "(see flightstatus.aviationstack.enabled) or the lookup failed");
        }

        FlightStatusPort.FlightStatusResult status = result.get();
        if (status.significantDelay()) {
            shipmentService.addTrackingEvent(shipmentId, new ShipmentDtos.AddTrackingEventRequest(
                    "EXCEPTION", null,
                    "Flight " + shipment.flightNumber() + " significantly delayed — departure +"
                    + status.departureDelayMinutes() + "min, arrival +" + status.arrivalDelayMinutes() + "min",
                    null));
        }

        return status;
    }
}
