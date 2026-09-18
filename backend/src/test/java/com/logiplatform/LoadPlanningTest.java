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
 * Same test cases verified independently in Python before this Java algorithm was
 * written (see RLS_VERIFICATION.md for the Python output) — re-verified here against
 * the actual Java implementation to catch any porting mistakes, plus a tenant
 * isolation case specific to this domain.
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

class LoadPlanningTest extends TenantTestSupport {

    @Autowired private ShipmentService shipmentService;
    @Autowired private VehicleService vehicleService;
    @Autowired private LoadPlanningService loadPlanningService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ShipmentResponse weighedShipment(String ref, int weightKg) {
        ShipmentResponse s = shipmentService.create(new CreateShipmentRequest(ref, "A", "B", "ROAD", null, null));
        return shipmentService.updateWeight(s.id(), new UpdateWeightRequest(weightKg));
    }

    @Test
    void perfectFitCase_matchesVerifiedPythonResult() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-LP1", "TRUCK", 1000));
        weighedShipment("S1", 600);
        weighedShipment("S2", 400);
        weighedShipment("S3", 500);
        weighedShipment("S4", 200);

        LoadPlanResponse plan = loadPlanningService.planLoad(vehicle.id());

        assertEquals(1000, plan.totalWeightLoadedKg(), "Python verified: perfect fit of 600+400=1000");
        assertEquals(100.0, plan.utilizationPercent(), 0.01);
    }

    @Test
    void noPerfectFitCase_matchesVerifiedPythonResult() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-LP2", "TRUCK", 750));
        weighedShipment("S1", 600);
        weighedShipment("S2", 400);
        weighedShipment("S3", 500);
        weighedShipment("S4", 200);

        LoadPlanResponse plan = loadPlanningService.planLoad(vehicle.id());

        assertEquals(700, plan.totalWeightLoadedKg(), "Python verified: best is 500+200=700, not 600 alone");
        assertEquals(2, plan.selected().size());
        assertTrue(plan.selected().stream().anyMatch(p -> p.referenceCode().equals("S3")));
        assertTrue(plan.selected().stream().anyMatch(p -> p.referenceCode().equals("S4")));
    }

    @Test
    void overCapacityItemIsExcluded_matchesVerifiedPythonResult() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-LP3", "VAN", 100));
        weighedShipment("Heavy", 150);
        weighedShipment("Light1", 50);
        weighedShipment("Light2", 40);

        LoadPlanResponse plan = loadPlanningService.planLoad(vehicle.id());

        assertEquals(90, plan.totalWeightLoadedKg());
        assertTrue(plan.excluded().stream().anyMatch(p -> p.referenceCode().equals("Heavy")));
        assertTrue(plan.selected().stream().noneMatch(p -> p.referenceCode().equals("Heavy")),
                "An item heavier than the entire vehicle capacity must never be selected");
    }

    @Test
    void noEligibleShipments_returnsEmptyPlanNotAnError() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-LP4", "TRUCK", 500));

        LoadPlanResponse plan = loadPlanningService.planLoad(vehicle.id());

        assertEquals(0, plan.totalWeightLoadedKg());
        assertTrue(plan.selected().isEmpty());
    }

    @Test
    void unweighedShipmentsAreExcludedFromConsideration() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-LP5", "TRUCK", 500));
        weighedShipment("Weighed", 300);
        shipmentService.create(new CreateShipmentRequest("Unweighed", "A", "B", "ROAD", null, null)); // no weight set

        LoadPlanResponse plan = loadPlanningService.planLoad(vehicle.id());

        assertEquals(1, plan.selected().size() + plan.excluded().size(),
                "Only the weighed shipment should even be considered — the unweighed one shouldn't appear at all");
    }

    @Test
    void tenantCannotPlanLoadForAnotherTenantsVehicle() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-LP6", "TRUCK", 500));

        setTenant(tenantB);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> loadPlanningService.planLoad(vehicle.id()));
    }
}
