package com.logiplatform;

import com.logiplatform.service.InvoiceTaxCalculator;
import com.logiplatform.service.RwandaInvoiceTaxPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RwandaInvoiceTaxPolicyTest {
    @Test
    void rwandaStatutoryChoicesHaveExpectedRates() {
        assertEquals(5, RwandaInvoiceTaxPolicy.treatments().size());
        assertEquals(0, new BigDecimal("18").compareTo(
                RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.STANDARD, null).rate()));
        assertEquals(0, RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.EXPORT, "Export declaration 101").rate().signum());
        assertEquals(0, RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.NONVAT, null).rate().signum());
    }

    @Test
    void specialTreatmentsRequireDocumentaryReference() {
        assertThrows(ResponseStatusException.class,
                () -> RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.EXPORT, null));
        assertThrows(ResponseStatusException.class,
                () -> RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.INBOUND_TRANSIT, ""));
        assertThrows(ResponseStatusException.class,
                () -> RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.EXEMPT, "   "));
        assertDoesNotThrow(() -> RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.EXEMPT, "RRA approval 45"));
    }

    @Test
    void arbitraryVatPercentagesCannotBecomeAnInvoiceTreatment() {
        assertThrows(ResponseStatusException.class,
                () -> RwandaInvoiceTaxPolicy.require("VAT5", "some ref"));
        assertThrows(ResponseStatusException.class,
                () -> RwandaInvoiceTaxPolicy.require("ZERO", ""));
    }

    @Test
    void zeroRatedAndExemptHaveZeroTaxWhileStandardIsEighteenPercent() {
        var standard = InvoiceTaxCalculator.calculate(new BigDecimal("100000"),
                RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.STANDARD, null).rate(),
                BigDecimal.ZERO, "RWF", false);
        var zero = InvoiceTaxCalculator.calculate(new BigDecimal("100000"),
                RwandaInvoiceTaxPolicy.require(RwandaInvoiceTaxPolicy.EXPORT, "AWB / export declaration").rate(),
                BigDecimal.ZERO, "RWF", false);
        assertEquals(0, new BigDecimal("118000").compareTo(standard.totalAmount()));
        assertEquals(0, new BigDecimal("18000").compareTo(standard.taxAmount()));
        assertEquals(0, new BigDecimal("100000").compareTo(zero.totalAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.taxAmount()));
    }
}
