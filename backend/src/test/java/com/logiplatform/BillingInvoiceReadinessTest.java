package com.logiplatform;

import com.logiplatform.controller.BillingController;
import com.logiplatform.service.BillingService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lightweight unit checks; no production DB or Spring context required. */
class BillingInvoiceReadinessTest {
    @Test
    void exposesMissingSetupWithoutIssuingInvoice() {
        BillingService service = mock(BillingService.class);
        when(service.invoiceTaxReadiness("USD"))
                .thenReturn(Map.of("ready", false, "message", "Tax setup required"));
        Map<String, Object> response = new BillingController(service).invoiceReadiness("USD");
        assertFalse((Boolean) response.get("ready"));
        assertEquals("Tax setup required", response.get("message"));
        verify(service).invoiceTaxReadiness("USD");
    }

    @Test
    void confirmsConfiguredRuleInRequestedCurrency() {
        BillingService service = mock(BillingService.class);
        when(service.invoiceTaxReadiness("RWF"))
                .thenReturn(Map.of("ready", true, "taxCode", "VAT18-AUTO"));
        Map<String, Object> response = new BillingController(service).invoiceReadiness("RWF");
        assertTrue((Boolean) response.get("ready"));
        verify(service).invoiceTaxReadiness("RWF");
    }
}
