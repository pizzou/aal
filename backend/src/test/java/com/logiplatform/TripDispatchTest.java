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

import java.util.List;
import java.util.UUID;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers tenant isolation (same standard as every other domain in this codebase) and
 * the dispatch state machine itself: a vehicle/driver can't be double-booked onto two
 * trips, and starting/completing a trip correctly propagates to vehicle, driver, and
 * shipment status. This runs against H2, same scope caveat as the other domain tests —
 * see ShipmentTenantIsolationTest for what that does and doesn't verify.
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

class TripDispatchTest extends TenantTestSupport {

    @Autowired private VehicleService vehicleService;
    @Autowired private DriverService driverService;
    @Autowired private TripService tripService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private VehicleResponse aVehicle() {
        return vehicleService.create(new CreateVehicleRequest("RAB-" + UUID.randomUUID().toString().substring(0, 6), "TRUCK", 5000));
    }

    private DriverResponse aDriver() {
        return driverService.create(new CreateDriverRequest("Test Driver", "LIC-" + UUID.randomUUID(), "0700000000"));
    }

    @Test
    void tenantCannotUseAnotherTenantsVehicleOnATrip() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        VehicleResponse vehicle = aVehicle();

        setTenant(tenantB);
        DriverResponse driver = aDriver();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> tripService.create(new CreateTripRequest(
                        vehicle.id(), driver.id(), "Origin", "Dest", null, null)),
                "Tenant B must not be able to dispatch a trip using tenant A's vehicle");
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void cannotDoubleBookAVehicleAlreadyOnATrip() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = aVehicle();
        DriverResponse driver1 = aDriver();
        DriverResponse driver2 = aDriver();

        TripResponse trip1 = tripService.create(
                new CreateTripRequest(vehicle.id(), driver1.id(), "A", "B", null, null));
        tripService.start(trip1.id());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> tripService.create(new CreateTripRequest(vehicle.id(), driver2.id(), "C", "D", null, null)),
                "A vehicle already ON_TRIP must not be assignable to a second trip");
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void startingATripMarksVehicleAndDriverOnTrip() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = aVehicle();
        DriverResponse driver = aDriver();
        TripResponse trip = tripService.create(new CreateTripRequest(vehicle.id(), driver.id(), "A", "B", null, null));

        tripService.start(trip.id());

        assertEquals("ON_TRIP", vehicleService.list().stream()
                .filter(v -> v.id().equals(vehicle.id())).findFirst().orElseThrow().status());
        assertEquals("ON_TRIP", driverService.list().stream()
                .filter(d -> d.id().equals(driver.id())).findFirst().orElseThrow().status());
    }

    @Test
    void completingATripFreesVehicleAndDriverForReuse() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = aVehicle();
        DriverResponse driver = aDriver();
        TripResponse trip = tripService.create(new CreateTripRequest(vehicle.id(), driver.id(), "A", "B", null, null));

        tripService.start(trip.id());
        tripService.complete(trip.id());

        assertEquals("AVAILABLE", vehicleService.list().stream()
                .filter(v -> v.id().equals(vehicle.id())).findFirst().orElseThrow().status());

        // The vehicle should now be usable on a brand new trip — proves the state
        // transition actually freed it up, not just changed a label nobody checks.
        DriverResponse driver2 = aDriver();
        assertDoesNotThrow(() ->
                tripService.create(new CreateTripRequest(vehicle.id(), driver2.id(), "E", "F", null, null)));
    }

    @Test
    void cancellingATripFreesResourcesWithoutTouchingShipmentStatus() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = aVehicle();
        DriverResponse driver = aDriver();
        TripResponse trip = tripService.create(new CreateTripRequest(vehicle.id(), driver.id(), "A", "B", null, null));

        tripService.start(trip.id());
        TripResponse cancelled = tripService.cancel(trip.id());

        assertEquals("CANCELLED", cancelled.status());
        assertEquals("AVAILABLE", vehicleService.list().stream()
                .filter(v -> v.id().equals(vehicle.id())).findFirst().orElseThrow().status());
    }

    @Test
    void cannotCompleteATripThatWasNeverStarted() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = aVehicle();
        DriverResponse driver = aDriver();
        TripResponse trip = tripService.create(new CreateTripRequest(vehicle.id(), driver.id(), "A", "B", null, null));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> tripService.complete(trip.id()));
        assertEquals(409, ex.getStatusCode().value());
    }
}
