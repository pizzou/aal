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

import java.util.UUID;




import static org.junit.jupiter.api.Assertions.*;

/**
 * The four aggregation queries here were verified directly against real Postgres,
 * seeded with an equivalent scenario, BEFORE this Java was written (see
 * RLS_VERIFICATION.md). This test builds the same kind of scenario through the
 * actual service layer (not raw SQL) against H2, so a Java-level mistake in wiring
 * ReportingService together would be caught even though H2 itself doesn't prove the
 * SQL dialect specifics that were already confirmed separately.
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

class ReportingTest extends TenantTestSupport {

    @Autowired private ShipmentService shipmentService;
    @Autowired private VehicleService vehicleService;
    @Autowired private DriverService driverService;
    @Autowired private TripService tripService;
    @Autowired private ReportingService reportingService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void dashboardForTenantWithNoDataReturnsZeroesNotErrors() {
        setTenant(UUID.randomUUID());
        DashboardResponse dashboard = reportingService.dashboard();

        assertTrue(dashboard.shipmentsByStatus().isEmpty());
        assertEquals(0, dashboard.tripStats().completed());
        assertEquals(0, dashboard.tripStats().cancelled());
    }

    @Test
    void shipmentStatusBreakdownReflectsActualCounts() {
        setTenant(UUID.randomUUID());
        shipmentService.create(new CreateShipmentRequest("R1", "A", "B", "ROAD", null, null));
        ShipmentResponse s2 = shipmentService.create(new CreateShipmentRequest("R2", "A", "B", "ROAD", null, null));
        shipmentService.updateStatus(s2.id(), new UpdateStatusRequest("DELIVERED"));

        var breakdown = reportingService.dashboard().shipmentsByStatus();
        assertTrue(breakdown.stream().anyMatch(s -> s.status().equals("PENDING") && s.count() == 1));
        assertTrue(breakdown.stream().anyMatch(s -> s.status().equals("DELIVERED") && s.count() == 1));
    }

    @Test
    void carrierExceptionRateComputesCorrectPercentage() {
        setTenant(UUID.randomUUID());
        ShipmentResponse s1 = shipmentService.create(new CreateShipmentRequest(
                "R3", "A", "B", "AIR", "TestCarrier", null));
        shipmentService.create(new CreateShipmentRequest("R4", "A", "B", "AIR", "TestCarrier", null));
        shipmentService.addTrackingEvent(s1.id(),
                new AddTrackingEventRequest("EXCEPTION", null, "test exception", null));

        var rates = reportingService.dashboard().carrierExceptionRates();
        CarrierExceptionRate rate = rates.stream()
                .filter(r -> r.carrierName().equals("TestCarrier")).findFirst().orElseThrow();

        assertEquals(2, rate.totalShipments());
        assertEquals(1, rate.shipmentsWithExceptions());
        assertEquals(50.0, rate.exceptionRatePercent(), 0.01, "1 of 2 shipments = 50%");
    }

    @Test
    void tripStatsReflectActualLifecycleTransitions() {
        setTenant(UUID.randomUUID());
        VehicleResponse v1 = vehicleService.create(new CreateVehicleRequest("RAB-R1", "TRUCK", 5000));
        VehicleResponse v2 = vehicleService.create(new CreateVehicleRequest("RAB-R2", "TRUCK", 5000));
        DriverResponse d1 = driverService.create(new CreateDriverRequest("Driver 1", "LIC-R1", null));
        DriverResponse d2 = driverService.create(new CreateDriverRequest("Driver 2", "LIC-R2", null));

        TripResponse t1 = tripService.create(new CreateTripRequest(v1.id(), d1.id(), "A", "B", null, null));
        tripService.start(t1.id());
        tripService.complete(t1.id());

        TripResponse t2 = tripService.create(new CreateTripRequest(v2.id(), d2.id(), "C", "D", null, null));
        tripService.cancel(t2.id());

        TripStats stats = reportingService.dashboard().tripStats();
        assertEquals(1, stats.completed());
        assertEquals(1, stats.cancelled());
        assertEquals(0, stats.planned());
    }

    @Test
    void dashboardIsTenantIsolated() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        shipmentService.create(new CreateShipmentRequest("TA-1", "A", "B", "ROAD", "CarrierA", null));

        setTenant(tenantB);
        DashboardResponse tenantBDashboard = reportingService.dashboard();

        assertTrue(tenantBDashboard.shipmentsByStatus().isEmpty(),
                "Tenant B's dashboard must not show tenant A's shipment counts");
        assertTrue(tenantBDashboard.carrierExceptionRates().stream()
                .noneMatch(r -> r.carrierName().equals("CarrierA")));
    }
}
