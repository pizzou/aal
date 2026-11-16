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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
    @Autowired private CargoDocumentRepository cargoDocuments;
    @Autowired @Qualifier("tenantJdbcTemplate") private JdbcTemplate tenantDb;

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
        assertEquals(shipment.referenceCode(), view.referenceCode());
        assertTrue(shipment.referenceCode().matches("AAL-SHP-\\d{4}-\\d{6}"));
        assertEquals(shipment.trackingNumber(), view.trackingNumber());
        assertEquals(shipment.trackingToken(), TrackingReferenceCode.toToken(view.trackingNumber()));
        assertEquals(view, publicTrackingService.findByReference(view.trackingNumber()));
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
        shipmentService.recordExternalMilestone(shipment.id(),
                com.logiplatform.model.TrackingEventType.DEPARTED_ORIGIN, null,
                "Mombasa", "Vessel departed");

        TenantContext.clear();
        PublicShipmentView view = publicTrackingService.findByToken(shipment.trackingToken());

        assertEquals(2, view.events().size(), "CREATED (from record creation) + DEPARTED_ORIGIN");
        assertEquals("CREATED", view.events().get(0).eventType());
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
        assertEquals(shipmentA.referenceCode(), publicTrackingService.findByToken(shipmentA.trackingToken()).referenceCode());
        assertEquals(shipmentB.referenceCode(), publicTrackingService.findByToken(shipmentB.trackingToken()).referenceCode());
    }

    @Test
    void publicTrackingDoesNotExposeDocumentsUnlessExplicitlyPublished() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                null, "Kigali", "Nairobi", "ROAD", null, null));
        CargoDocument hidden = new CargoDocument(TenantContext.getTenantId(), shipment.id(),
                "INTERNAL_CUSTOMS_FILE", "INTERNAL", null, null);
        CargoDocument visible = new CargoDocument(TenantContext.getTenantId(), shipment.id(),
                "PUBLIC_DELIVERY_NOTE", "DELIVERY_NOTE", null, null);
        visible.setLifecycle(1, null, null, true, null, null, null);
        // DRAFT documents are not published, even when incorrectly flagged visible.
        CargoDocument draft = new CargoDocument(TenantContext.getTenantId(), shipment.id(),
                "UNAPPROVED_DRAFT", "DRAFT", null, null);
        draft.setLifecycle(1, null, null, true, null, null, null);
        // The lifecycle service owns publication; test the DB visibility predicate
        // without bypassing the stored default document status.
        cargoDocuments.saveAndFlush(hidden);
        cargoDocuments.saveAndFlush(visible);
        cargoDocuments.saveAndFlush(draft);
        tenantDb.update("UPDATE cargo_documents SET status='ISSUED' WHERE id=? AND tenant_id=?",
                visible.getId(), TenantContext.getTenantId());
        TenantContext.clear();

        PublicShipmentView result = publicTrackingService.findByReference(shipment.trackingNumber());
        assertEquals(1, result.documents().size(), "Only customer-visible, issued documents appear");
        assertEquals("PUBLIC_DELIVERY_NOTE", result.documents().get(0).documentType());
    }

    @Test
    void malformedOrGuessableReferencesDoNotRevealShipments() {
        TenantContext.clear();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> publicTrackingService.findByReference("AAL-SHP-2026-000001"));
        assertEquals(404, ex.getStatusCode().value());
    }
}
