package com.logiplatform;

import com.logiplatform.model.*;
import com.logiplatform.dto.*;
import com.logiplatform.repository.*;
import com.logiplatform.service.*;
import com.logiplatform.controller.*;

import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;


import static org.junit.jupiter.api.Assertions.*;

/**
 * The test profile has no AviationStack key configured (same as any environment
 * that hasn't deliberately set flightstatus.aviationstack.enabled=true), so
 * NoOpFlightStatusAdapter is active — these tests verify that safe-default behavior,
 * not a real API call (which this sandbox cannot make anyway — see
 * RLS_VERIFICATION.md for what WAS verified: the real AviationStack response schema
 * and delay-parsing logic, independently, before this was wired together).
 */
import static com.logiplatform.dto.ShipmentDtos.*;
import static com.logiplatform.dto.ReportingDtos.*;
import static com.logiplatform.dto.TmsDtos.*;
import static com.logiplatform.dto.GpsDtos.*;
import static com.logiplatform.dto.LoadPlanDtos.*;
import static com.logiplatform.dto.RatingDtos.*;
import static com.logiplatform.dto.PublicTrackingDtos.*;
import static com.logiplatform.dto.WarehouseDtos.*;
import static com.logiplatform.dto.SensorDtos.*;
import static com.logiplatform.dto.AuthDtos.*;

@SpringBootTest
@ActiveProfiles("test")

class FlightStatusTest extends TenantTestSupport {

    @Autowired private ShipmentService shipmentService;
    @Autowired private FlightDelayCheckService flightDelayCheckService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void checkingStatusWithNoFlightNumberSetIsRejected() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "FLT-001", "A", "B", "AIR", null, null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> flightDelayCheckService.checkAndFlagDelay(shipment.id()));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void withNoProviderConfiguredStatusCheckReturnsServiceUnavailableNotACrash() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "FLT-002", "A", "B", "AIR", "Kenya Airways Cargo", null));
        shipmentService.updateFlightNumber(shipment.id(), new UpdateFlightNumberRequest("KQ100"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> flightDelayCheckService.checkAndFlagDelay(shipment.id()));
        assertEquals(503, ex.getStatusCode().value());
    }

    @Test
    void tenantCannotCheckFlightStatusForAnotherTenantsShipment() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "FLT-003", "A", "B", "AIR", null, null));
        shipmentService.updateFlightNumber(shipment.id(), new UpdateFlightNumberRequest("KQ100"));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> flightDelayCheckService.checkAndFlagDelay(shipment.id()));
        assertEquals(404, ex.getStatusCode().value(),
                "Must 404 (shipment not found for this tenant) before ever reaching the flight lookup");
    }
}
