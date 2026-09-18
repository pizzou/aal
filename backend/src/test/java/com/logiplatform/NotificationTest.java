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
 * With no SMTP configured (the default in the test profile, same as any environment
 * that hasn't deliberately set notifications.smtp.enabled=true), every notification
 * should land as LOGGED_ONLY — never silently dropped, never throwing an error that
 * would break the shipment operation that triggered it.
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

class NotificationTest extends TenantTestSupport {

    @Autowired private ShipmentService shipmentService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void deliveryWithNoEmailConfiguredLogsButDoesNotSend() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "NOTIF-001", "A", "B", "ROAD", null, null));

        shipmentService.updateStatus(shipment.id(), new UpdateStatusRequest("DELIVERED"));

        var history = shipmentService.notificationHistory(shipment.id(), PageRequest.of(0, 10));
        assertEquals(1, history.getTotalElements());
        assertEquals("LOGGED_ONLY", history.getContent().get(0).status());
        assertNull(history.getContent().get(0).recipient());
    }

    @Test
    void deliveryWithEmailConfiguredStillLogsOnlyWithoutRealSmtp() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "NOTIF-002", "A", "B", "ROAD", null, null));
        shipmentService.updateNotificationEmail(shipment.id(),
                new UpdateNotificationEmailRequest("customer@example.com"));

        shipmentService.updateStatus(shipment.id(), new UpdateStatusRequest("DELIVERED"));

        var history = shipmentService.notificationHistory(shipment.id(), PageRequest.of(0, 10));
        assertEquals(1, history.getTotalElements());
        assertEquals("LOGGED_ONLY", history.getContent().get(0).status());
        assertEquals("customer@example.com", history.getContent().get(0).recipient());
    }

    @Test
    void nonDeliveryStatusChangesDoNotTriggerNotifications() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "NOTIF-003", "A", "B", "ROAD", null, null));

        shipmentService.updateStatus(shipment.id(), new UpdateStatusRequest("IN_TRANSIT"));

        var history = shipmentService.notificationHistory(shipment.id(), PageRequest.of(0, 10));
        assertEquals(0, history.getTotalElements(), "IN_TRANSIT is not a notification-triggering status");
    }

    @Test
    void exceptionTrackingEventTriggersNotification() {
        setTenant(UUID.randomUUID());
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "NOTIF-004", "A", "B", "AIR", null, null));

        shipmentService.addTrackingEvent(shipment.id(),
                new AddTrackingEventRequest("EXCEPTION", "Customs", "Held for inspection", null));

        var history = shipmentService.notificationHistory(shipment.id(), PageRequest.of(0, 10));
        assertEquals(1, history.getTotalElements());
        assertTrue(history.getContent().get(0).subject().contains("Attention needed"));
    }

    @Test
    void notificationHistoryIsTenantIsolated() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        ShipmentResponse shipment = shipmentService.create(new CreateShipmentRequest(
                "NOTIF-005", "A", "B", "ROAD", null, null));
        shipmentService.updateStatus(shipment.id(), new UpdateStatusRequest("DELIVERED"));

        setTenant(tenantB);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> shipmentService.notificationHistory(shipment.id(), PageRequest.of(0, 10)));
        assertEquals(404, ex.getStatusCode().value(),
                "Tenant B must not be able to read tenant A's shipment's notification history");
    }
}
