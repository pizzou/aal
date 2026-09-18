package com.logiplatform;

import com.logiplatform.dto.SensorDtos.*;
import com.logiplatform.dto.ShipmentDtos.CreateShipmentRequest;
import com.logiplatform.dto.ShipmentDtos.ShipmentResponse;
import com.logiplatform.service.SensorMonitoringService;
import com.logiplatform.service.ShipmentService;
import com.logiplatform.tenancy.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class SensorMonitoringTest extends TenantTestSupport {

        @Autowired
        private ShipmentService shipmentService;

        @Autowired
        private SensorMonitoringService sensorMonitoringService;

        @AfterEach
        void clearTenant() {
                TenantContext.clear();
        }

        @Test
        void readingWithinThresholdDoesNotCreateException() {
                setTenant(UUID.randomUUID());

                ShipmentResponse shipment = shipmentService.create(
                                new CreateShipmentRequest(
                                                "SENSOR-001",
                                                "A",
                                                "B",
                                                "AIR",
                                                "Cargo Air",
                                                null));

                sensorMonitoringService.setThreshold(
                                shipment.id(),
                                new SetThresholdRequest(
                                                -18.0,
                                                -15.0,
                                                null,
                                                null));

                ReadingResponse reading = sensorMonitoringService.recordReading(
                                shipment.id(),
                                new RecordReadingRequest(
                                                -16.5,
                                                null,
                                                null,
                                                null));

                assertFalse(reading.violatesThreshold());

                var history = shipmentService.trackingHistory(
                                shipment.id());

                assertEquals(
                                1,
                                history.size(),
                                "Only the initial BOOKED event — no exception should be added");
        }

        @Test
        void readingOutsideThresholdAutomaticallyCreatesExceptionTrackingEvent() {
                setTenant(UUID.randomUUID());

                ShipmentResponse shipment = shipmentService.create(
                                new CreateShipmentRequest(
                                                "SENSOR-002",
                                                "A",
                                                "B",
                                                "AIR",
                                                "Cargo Air",
                                                null));

                sensorMonitoringService.setThreshold(
                                shipment.id(),
                                new SetThresholdRequest(
                                                -18.0,
                                                -15.0,
                                                null,
                                                null));

                ReadingResponse reading = sensorMonitoringService.recordReading(
                                shipment.id(),
                                new RecordReadingRequest(
                                                -5.0,
                                                null,
                                                null,
                                                null));

                assertTrue(reading.violatesThreshold());

                var history = shipmentService.trackingHistory(
                                shipment.id());

                assertEquals(
                                2,
                                history.size(),
                                "BOOKED + an auto-generated EXCEPTION event");

                assertEquals(
                                "EXCEPTION",
                                history.get(1).eventType());

                assertTrue(
                                history.get(1).notes().contains("-5.0"),
                                "The exception note should reference the actual reading");
        }

        @Test
        void readingWithNoThresholdConfiguredNeverFlagsViolation() {
                setTenant(UUID.randomUUID());

                ShipmentResponse shipment = shipmentService.create(
                                new CreateShipmentRequest(
                                                "SENSOR-003",
                                                "A",
                                                "B",
                                                "ROAD",
                                                null,
                                                null));

                ReadingResponse reading = sensorMonitoringService.recordReading(
                                shipment.id(),
                                new RecordReadingRequest(
                                                999.0,
                                                null,
                                                null,
                                                null));

                assertFalse(reading.violatesThreshold());
        }

        @Test
        void humidityViolationAlsoTriggersException() {
                setTenant(UUID.randomUUID());

                ShipmentResponse shipment = shipmentService.create(
                                new CreateShipmentRequest(
                                                "SENSOR-004",
                                                "A",
                                                "B",
                                                "SEA",
                                                null,
                                                null));

                sensorMonitoringService.setThreshold(
                                shipment.id(),
                                new SetThresholdRequest(
                                                null,
                                                null,
                                                30.0,
                                                60.0));

                ReadingResponse reading = sensorMonitoringService.recordReading(
                                shipment.id(),
                                new RecordReadingRequest(
                                                null,
                                                85.0,
                                                null,
                                                null));

                assertTrue(reading.violatesThreshold());
        }

        @Test
        void historyReflectsViolationFlagForPastReadingsToo() {
                setTenant(UUID.randomUUID());

                ShipmentResponse shipment = shipmentService.create(
                                new CreateShipmentRequest(
                                                "SENSOR-005",
                                                "A",
                                                "B",
                                                "AIR",
                                                null,
                                                null));

                sensorMonitoringService.recordReading(
                                shipment.id(),
                                new RecordReadingRequest(
                                                -2.0,
                                                null,
                                                null,
                                                null));

                sensorMonitoringService.setThreshold(
                                shipment.id(),
                                new SetThresholdRequest(
                                                -18.0,
                                                -15.0,
                                                null,
                                                null));

                var history = sensorMonitoringService.history(
                                shipment.id(),
                                PageRequest.of(0, 10));

                assertTrue(
                                history.getContent()
                                                .get(0)
                                                .violatesThreshold(),
                                "history() re-evaluates against the current threshold, so a reading that predates the threshold still shows as violating it now");
        }
}