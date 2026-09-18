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

import java.util.UUID;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers two separate risks in the WMS domain:
 *  1. Tenant isolation — same requirement and same importance as ShipmentTenantIsolationTest.
 *  2. Stock integrity — quantity_on_hand must never go negative and must always match
 *     the sum of its movement ledger; a WMS that can silently drift from its own audit
 *     trail is worse than useless to a client doing a physical stock count.
 *
 * NOTE ON SCOPE: like the shipment isolation tests, this runs against H2 and verifies
 * application-layer scoping only, not Postgres RLS. It also does NOT test the
 * PESSIMISTIC_WRITE row lock under real concurrent threads — H2's locking semantics
 * don't reliably mirror Postgres's, so a genuine concurrent-movement stress test needs
 * to run against real Postgres (Testcontainers) before this is trusted at the "thousands
 * of vehicles / high write volume" scale this platform is meant for.
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

class WarehouseInventoryTenantIsolationTest extends TenantTestSupport {

    @Autowired private WarehouseService warehouseService;
    @Autowired private InventoryService inventoryService;
    @Autowired private StockMovementService stockMovementService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantCannotAccessAnotherTenantsWarehouse() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        WarehouseResponse warehouse = warehouseService.create(
                new CreateWarehouseRequest("Kigali Hub", "KG 7 Ave"));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> inventoryService.create(warehouse.id(),
                        new CreateInventoryItemRequest("SKU-1", "Widget", 5)));
        assertEquals(404, ex.getStatusCode().value(),
                "Tenant B must not be able to add inventory to tenant A's warehouse");
    }

    @Test
    void inventoryListDoesNotLeakAcrossTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        WarehouseResponse warehouseA = warehouseService.create(new CreateWarehouseRequest("Warehouse A", "Addr A"));
        inventoryService.create(warehouseA.id(), new CreateInventoryItemRequest("SKU-A", "Item A", 0));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> inventoryService.list(warehouseA.id(), PageRequest.of(0, 10)),
                "Tenant B must get 404, not an empty/leaked view of tenant A's warehouse");
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void stockCannotGoNegative() {
        UUID tenant = UUID.randomUUID();
        setTenant(tenant);

        WarehouseResponse warehouse = warehouseService.create(new CreateWarehouseRequest("Main", "Addr"));
        InventoryItemResponse item = inventoryService.create(
                warehouse.id(), new CreateInventoryItemRequest("SKU-X", "Item X", 0));

        stockMovementService.recordMovement(item.id(), new RecordMovementRequest("IN", 10, "initial stock"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> stockMovementService.recordMovement(
                        item.id(), new RecordMovementRequest("OUT", 15, "overpick")),
                "Removing more stock than is on hand must be rejected, not go negative");
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void quantityOnHandMatchesNetOfMovements() {
        UUID tenant = UUID.randomUUID();
        setTenant(tenant);

        WarehouseResponse warehouse = warehouseService.create(new CreateWarehouseRequest("Main2", "Addr"));
        InventoryItemResponse item = inventoryService.create(
                warehouse.id(), new CreateInventoryItemRequest("SKU-Y", "Item Y", 0));

        stockMovementService.recordMovement(item.id(), new RecordMovementRequest("IN", 100, "receiving"));
        stockMovementService.recordMovement(item.id(), new RecordMovementRequest("OUT", 30, "shipment pick"));
        InventoryItemResponse afterAdjustment = stockMovementService.recordMovement(
                item.id(), new RecordMovementRequest("ADJUSTMENT_OUT", 5, "cycle count shrinkage"));

        assertEquals(65, afterAdjustment.quantityOnHand(), "100 - 30 - 5 = 65");
    }
}
