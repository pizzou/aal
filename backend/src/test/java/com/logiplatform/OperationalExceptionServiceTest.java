package com.logiplatform;

import com.logiplatform.dto.OperationsDtos.ExceptionRequest;
import com.logiplatform.repository.OperationalExceptionRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.TripRepository;
import com.logiplatform.repository.TripShipmentRepository;
import com.logiplatform.repository.VehicleRepository;
import com.logiplatform.service.NotificationService;
import com.logiplatform.service.OperationalExceptionService;
import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationalExceptionServiceTest {
    @Mock OperationalExceptionRepository exceptions;
    @Mock ShipmentRepository shipments;
    @Mock TripRepository trips;
    @Mock VehicleRepository vehicles;
    @Mock TripShipmentRepository tripShipments;
    @Mock NotificationService notifications;

    private OperationalExceptionService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContext.setTenantId(tenantId);
        service = new OperationalExceptionService(exceptions, shipments, trips, vehicles,
                tripShipments, notifications);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsUnknownSeverityBeforePersistingIncident() {
        ExceptionRequest request = new ExceptionRequest(null, null, null,
                "CARGO_DAMAGE", "URGENT", null, "Package damaged in transit");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.open(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(exceptions, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotAllowAnExceptionToReferenceAnotherTenantsShipment() {
        UUID foreignShipment = UUID.randomUUID();
        when(shipments.findByIdAndTenantId(foreignShipment, tenantId)).thenReturn(Optional.empty());
        ExceptionRequest request = new ExceptionRequest(foreignShipment, null, null,
                "CARGO_DAMAGE", "HIGH", null, "Package damaged in transit");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.open(request));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(exceptions, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(notifications, never()).notifyAfterCommit(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
