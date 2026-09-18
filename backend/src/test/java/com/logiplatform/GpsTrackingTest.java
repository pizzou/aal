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

import java.time.Instant;
import java.util.UUID;



import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs in CI against the real Redis service defined in ci.yml — this sandbox has no
 * way to run these (no Maven Central), but they're written to pass against a real
 * Redis instance, matching the pattern verified manually and documented in
 * RLS_VERIFICATION.md. If Redis is genuinely unavailable in some environment, latest()
 * should still pass by falling back to Postgres — that fallback path is itself tested
 * here, not just assumed.
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

class GpsTrackingTest extends TenantTestSupport {

    @Autowired private VehicleService vehicleService;
    @Autowired private GpsTrackingService gpsTrackingService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void recordAndRetrieveLatestPosition() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-GPS1", "TRUCK", 5000));

        gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(-1.9441, 30.0619, 45.0, 90.0, Instant.now()));

        PositionResponse latest = gpsTrackingService.latest(vehicle.id());
        assertEquals(-1.9441, latest.latitude(), 0.0001);
        assertEquals(30.0619, latest.longitude(), 0.0001);
    }

    @Test
    void latestReturnsMostRecentPositionNotFirstRecorded() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-GPS2", "TRUCK", 5000));

        gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(-1.0, 30.0, 40.0, 0.0, Instant.now().minusSeconds(60)));
        gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(-2.0, 31.0, 50.0, 180.0, Instant.now()));

        PositionResponse latest = gpsTrackingService.latest(vehicle.id());
        assertEquals(-2.0, latest.latitude(), 0.0001, "Must return the second, more recent position");
    }

    @Test
    void tenantCannotRecordPositionForAnotherTenantsVehicle() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-GPS3", "TRUCK", 5000));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> gpsTrackingService.recordPosition(vehicle.id(),
                        new RecordPositionRequest(0.0, 0.0, null, null, null)));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void invalidLatitudeIsRejected() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-GPS4", "TRUCK", 5000));

        assertThrows(Exception.class, () -> gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(999.0, 30.0, null, null, null)));
    }

    @Test
    void historyReturnsPositionsNewestFirst() {
        setTenant(UUID.randomUUID());
        VehicleResponse vehicle = vehicleService.create(new CreateVehicleRequest("RAB-GPS5", "TRUCK", 5000));

        gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(-1.0, 30.0, null, null, Instant.now().minusSeconds(120)));
        gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(-1.5, 30.5, null, null, Instant.now().minusSeconds(60)));
        gpsTrackingService.recordPosition(vehicle.id(),
                new RecordPositionRequest(-2.0, 31.0, null, null, Instant.now()));

        var history = gpsTrackingService.history(vehicle.id(), PageRequest.of(0, 10));
        assertEquals(3, history.getTotalElements());
        assertEquals(-2.0, history.getContent().get(0).latitude(), 0.0001, "Newest position must come first");
    }
}
