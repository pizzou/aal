package com.logiplatform;

import com.logiplatform.model.ShipmentStatus;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.TransportMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

class ShipmentStatusWorkflowTest {
    @Test
    void onlyPermitsForwardOperationalTransitions() {
        assertTrue(ShipmentStatus.PENDING.canTransitionTo(ShipmentStatus.BOOKED));
        assertTrue(ShipmentStatus.BOOKED.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertTrue(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.ARRIVED));
        assertFalse(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.OUT_FOR_DELIVERY),
                "Final mile must not begin until the shipment has arrived");
        assertFalse(ShipmentStatus.ON_HOLD.canTransitionTo(ShipmentStatus.DELIVERED),
                "A held shipment cannot jump directly to delivered");
        assertTrue(ShipmentStatus.ARRIVED.canTransitionTo(ShipmentStatus.IN_TRANSIT),
                "An intermediate hub arrival may be dispatched on the next trip leg");
        assertFalse(ShipmentStatus.DELIVERED.canTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertFalse(ShipmentStatus.CANCELLED.canTransitionTo(ShipmentStatus.BOOKED));
    }

    @Test
    void inTransitCannotSkipArrivalAndHoldCannotEraseThatRequirement() {
        Shipment shipment = new Shipment(UUID.randomUUID(), "HOLD-1", "Origin", "Destination",
                TransportMode.ROAD, null, null);
        shipment.updateStatus(ShipmentStatus.BOOKED);
        shipment.updateStatus(ShipmentStatus.IN_TRANSIT);
        assertThrows(IllegalStateException.class,
                () -> shipment.updateStatus(ShipmentStatus.OUT_FOR_DELIVERY));

        shipment.updateStatus(ShipmentStatus.ON_HOLD);
        assertThrows(IllegalStateException.class,
                () -> shipment.updateStatus(ShipmentStatus.OUT_FOR_DELIVERY));
        shipment.updateStatus(ShipmentStatus.ARRIVED);
        shipment.updateStatus(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void aHoldAfterFailedFinalMileCanResumeThatSameStage() {
        Shipment shipment = new Shipment(UUID.randomUUID(), "HOLD-2", "Origin", "Destination",
                TransportMode.ROAD, null, null);
        shipment.updateStatus(ShipmentStatus.BOOKED);
        shipment.updateStatus(ShipmentStatus.IN_TRANSIT);
        shipment.updateStatus(ShipmentStatus.ARRIVED);
        shipment.updateStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        shipment.updateStatus(ShipmentStatus.ON_HOLD);
        shipment.updateStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipment.getStatus());
    }

    @Test
    void distinguishesCustomsHoldFromSuccessfulClearance() {
        assertTrue(ShipmentStatus.CUSTOMS.canTransitionTo(ShipmentStatus.CUSTOMS_CLEARED));
        assertFalse(ShipmentStatus.CUSTOMS_CLEARED.canTransitionTo(ShipmentStatus.CUSTOMS));
        assertFalse(ShipmentStatus.CUSTOMS.canTransitionTo(ShipmentStatus.DELIVERED));
    }

    @Test
    void sameStatusIsAnIdempotentRetry() {
        assertTrue(ShipmentStatus.IN_TRANSIT.canTransitionTo(ShipmentStatus.IN_TRANSIT));
    }
}
