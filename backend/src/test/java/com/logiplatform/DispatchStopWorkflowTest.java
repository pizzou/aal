package com.logiplatform;

import com.logiplatform.model.DispatchStop;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DispatchStopWorkflowTest {
    private DispatchStop stop() {
        return new DispatchStop(UUID.randomUUID(), UUID.randomUUID(), null, 1, "PICKUP", "Kigali",
                -1.95, 30.06, null, null, null, null, null);
    }

    @Test
    void aStopMustArriveBeforeItCanDepartAndBothTimesArePreserved() {
        DispatchStop stop = stop();
        Instant arrived = Instant.parse("2026-10-01T08:00:00Z");
        Instant departed = Instant.parse("2026-10-01T08:15:00Z");

        assertThrows(IllegalStateException.class, () -> stop.depart(departed));
        stop.arrive(arrived);
        stop.depart(departed);

        assertEquals("COMPLETED", stop.getStatus());
        assertEquals(arrived, stop.getArrivedAt());
        assertEquals(departed, stop.getDepartedAt());
        assertEquals(departed, stop.getActualAt());
    }

    @Test
    void skippingRequiresAReasonAndCannotSkipAnArrivedStop() {
        DispatchStop stop = stop();
        assertThrows(IllegalArgumentException.class, () -> stop.skip("  "));
        stop.skip("Road closure");
        assertEquals("SKIPPED", stop.getStatus());
        assertThrows(IllegalStateException.class, () -> stop.arrive(Instant.now()));
    }

    @Test
    void duplicateActionRetriesDoNotRewriteOperationalTimes() {
        DispatchStop stop = stop();
        Instant arrived = Instant.parse("2026-10-01T08:00:00Z");
        stop.arrive(arrived);
        stop.arrive(arrived.plusSeconds(60));
        assertEquals(arrived, stop.getArrivedAt());
    }
}
