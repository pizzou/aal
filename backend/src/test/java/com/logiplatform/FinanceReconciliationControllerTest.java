package com.logiplatform;

import com.logiplatform.controller.FinanceReconciliationController;
import com.logiplatform.service.FinanceReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FinanceReconciliationControllerTest {
    @Mock
    private FinanceReconciliationService service;

    @InjectMocks
    private FinanceReconciliationController controller;

    @Test
    void omittedDateIsDelegatedAsNullSoServiceUsesKigaliBusinessDate() {
        controller.reconcile(null, "USD");
        verify(service).reconcile(null, "USD");
    }

    @Test
    void blankDateIsDelegatedAsNullSoServiceUsesKigaliBusinessDate() {
        controller.reconcile("   ", "USD");
        verify(service).reconcile(null, "USD");
    }

    @Test
    void explicitDateIsPassedThroughWithoutChangingHistoricalDatePolicy() {
        LocalDate requestedDate = LocalDate.of(2026, 10, 18);
        controller.reconcile("2026-10-18", "USD");
        verify(service).reconcile(requestedDate, "USD");
    }

    @Test
    void invalidExplicitDateIsRejected() {
        assertThrows(java.time.format.DateTimeParseException.class,
                () -> controller.reconcile("not-a-date", "USD"));
    }
}
