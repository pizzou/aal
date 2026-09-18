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
 * The tracking token IS the access control for this endpoint, so getting these
 * cases wrong is a real security bug, not just a UX one:
 *  - the correct token must work with NO authentication and NO tenant context at all
 *  - a wrong/random token must 404, not error differently in a way that leaks
 *    whether a token format was "close" to valid
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

class PublicTrackingTest extends TenantTestSupport {

    @Autowired private ShipmentService shipmentService;
    @Autowired private PublicTrackingService publicTrackingService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tokenLookupWorksWithNoTenantContextSetAtAll() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "PUB-001", "Kigali", "Nairobi", "ROAD", "Local Trucking Co", null));

        // This is the entire point of the feature: clear the tenant context, exactly
        // as a real unauthenticated public request would have none, and confirm the
        // lookup still works using only the token.
        TenantContext.clear();

        PublicShipmentView view = publicTrackingService.findByToken(shipment.trackingToken());
        assertEquals("PUB-001", view.referenceCode());
        assertEquals("ROAD", view.transportMode());
    }

    @Test
    void wrongTokenReturns404NotSomeOtherError() {
        TenantContext.clear();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> publicTrackingService.findByToken(UUID.randomUUID()));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void publicViewIncludesTrackingEventsInOrder() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "PUB-002", "Mombasa", "Kampala", "ROAD", null, null));
        shipmentService.addTrackingEvent(shipment.id(),
                new AddTrackingEventRequest("DEPARTED_ORIGIN", "Mombasa", null, null));

        TenantContext.clear();
        PublicShipmentView view = publicTrackingService.findByToken(shipment.trackingToken());

        assertEquals(2, view.events().size(), "BOOKED (from creation) + DEPARTED_ORIGIN");
        assertEquals("BOOKED", view.events().get(0).eventType());
        assertEquals("DEPARTED_ORIGIN", view.events().get(1).eventType());
    }

    @Test
    void differentShipmentsNeverShareATokenEvenAcrossTenants() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipmentA = shipmentService.create(new CreateShipmentRequest(
                "PUB-003", "A", "B", "ROAD", null, null));

        setTenant(UUID.randomUUID());
        ShipmentResponse shipmentB = shipmentService.create(new CreateShipmentRequest(
                "PUB-004", "C", "D", "ROAD", null, null));

        assertNotEquals(shipmentA.trackingToken(), shipmentB.trackingToken());

        TenantContext.clear();
        assertEquals("PUB-003", publicTrackingService.findByToken(shipmentA.trackingToken()).referenceCode());
        assertEquals("PUB-004", publicTrackingService.findByToken(shipmentB.trackingToken()).referenceCode());
    }
}
