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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;


import static org.junit.jupiter.api.Assertions.*;


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

class ShipmentTenantIsolationTest extends TenantTestSupport {

    @Autowired
    private ShipmentService shipmentService;

    private static CreateShipmentRequest roadShipment(String ref) {
        return new CreateShipmentRequest(ref, "Kigali", "Nairobi", "ROAD", "Local Trucking Co", null);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantCannotReadAnotherTenantsShipment() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        ShipmentResponse created = shipmentService.create(roadShipment("SHP-A1"));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> shipmentService.get(created.id()));
        assertEquals(404, ex.getStatusCode().value(),
                "Tenant B must get a 404, not tenant A's shipment data");
    }

    @Test
    void tenantListDoesNotIncludeOtherTenantsShipments() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        shipmentService.create(roadShipment("SHP-A2"));

        setTenant(tenantB);
        shipmentService.create(roadShipment("SHP-B1"));

        var tenantBShipments = shipmentService.list(PageRequest.of(0, 50));

        assertTrue(tenantBShipments.getContent().stream()
                        .allMatch(s -> s.referenceCode().equals("SHP-B1")),
                "Tenant B's shipment list must contain only tenant B's shipments");
    }

    @Test
    void referenceCodeUniquenessIsScopedPerTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        shipmentService.create(roadShipment("SHP-SHARED"));

        setTenant(tenantB);
        assertDoesNotThrow(() -> shipmentService.create(roadShipment("SHP-SHARED")));
    }

    @Test
    void accessingTenantScopedMethodWithoutTenantContextFailsClosed() {
        TenantContext.clear();
        assertThrows(IllegalStateException.class,
                () -> shipmentService.list(PageRequest.of(0, 10)),
                "Calling a tenant-scoped method with no tenant set must fail loudly, never silently return all rows");
    }

    @Test
    void createsWithAirCarrierDetailsAndBookedTrackingEvent() {
        setTenant(UUID.randomUUID());

        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "AWB-001", "Nairobi (NBO)", "Amsterdam (AMS)", "AIR",
                "Kenya Airways Cargo", "706-12345670"));

        assertEquals("AIR", shipment.transportMode());
        assertEquals("706-12345670", shipment.carrierReferenceNumber());

        List<TrackingEventResponse> history = shipmentService.trackingHistory(shipment.id());
        assertEquals(1, history.size(), "A new shipment should start with exactly one BOOKED event");
        assertEquals("BOOKED", history.get(0).eventType());
    }

    @Test
    void trackingHistoryOrderedByOccurrenceNotInsertion() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "BOL-001", "Mombasa Port", "Rotterdam Port", "SEA", "Maersk", "MAEU1234567"));

        shipmentService.addTrackingEvent(shipment.id(), new AddTrackingEventRequest(
                "DEPARTED_ORIGIN", "Mombasa Port", "Vessel departed", null));
        shipmentService.addTrackingEvent(shipment.id(), new AddTrackingEventRequest(
                "IN_TRANSIT", "Indian Ocean", null, null));

        List<TrackingEventResponse> history = shipmentService.trackingHistory(shipment.id());
        assertEquals(3, history.size()); // BOOKED + the two above
        assertEquals("BOOKED", history.get(0).eventType());
        assertEquals("IN_TRANSIT", history.get(2).eventType());
    }

    @Test
    void tenantCannotAddTrackingEventToAnotherTenantsShipment() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        ShipmentResponse shipment = shipmentService.create(roadShipment("SHP-A3"));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> shipmentService.addTrackingEvent(shipment.id(),
                        new AddTrackingEventRequest("DELIVERED", null, null, null)));
        assertEquals(404, ex.getStatusCode().value());
    }
}
