package com.logiplatform;

import com.logiplatform.service.InvoiceTaxCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoiceTaxCalculatorTest {
    @Test
    void exclusiveVatRoundsTaxToCurrencyMinorUnitsBeforeTotalIsCreated() {
        InvoiceTaxCalculator.Calculation result = InvoiceTaxCalculator.calculate(
                new BigDecimal("100.05"), new BigDecimal("18"), BigDecimal.ZERO, "USD", false);

        assertAmount("100.05", result.taxableAmount());
        assertAmount("18.01", result.taxAmount());
        assertAmount("118.06", result.totalAmount());
        assertAmount("118.06", result.netPayable());
        assertEquals(2, result.currencyScale());
    }

    @Test
    void inclusiveVatIsExtractedFromGrossWithoutAddingTaxTwice() {
        InvoiceTaxCalculator.Calculation result = InvoiceTaxCalculator.calculate(
                new BigDecimal("118.00"), new BigDecimal("18"), BigDecimal.ZERO, "USD", true);

        assertAmount("100.00", result.taxableAmount());
        assertAmount("18.00", result.taxAmount());
        assertAmount("118.00", result.totalAmount());
        assertAmount("118.00", result.taxableAmount().add(result.taxAmount()));
    }

    @Test
    void zeroDecimalCurrencyUsesWholeCurrencyUnitsAndStillReconciles() {
        InvoiceTaxCalculator.Calculation result = InvoiceTaxCalculator.calculate(
                new BigDecimal("100000"), new BigDecimal("18"), BigDecimal.ZERO, "RWF", false);

        assertAmount("100000", result.taxableAmount());
        assertAmount("18000", result.taxAmount());
        assertAmount("118000", result.totalAmount());
        assertEquals(0, result.currencyScale());
    }

    @Test
    void inclusiveZeroDecimalCurrencyReconcilesRoundedTaxToGross() {
        InvoiceTaxCalculator.Calculation result = InvoiceTaxCalculator.calculate(
                new BigDecimal("100"), new BigDecimal("18"), BigDecimal.ZERO, "RWF", true);

        assertAmount("85", result.taxableAmount());
        assertAmount("15", result.taxAmount());
        assertAmount("100", result.totalAmount());
    }

    @Test
    void threeDecimalCurrencyRetainsItsMinorUnits() {
        InvoiceTaxCalculator.Calculation result = InvoiceTaxCalculator.calculate(
                new BigDecimal("100.001"), new BigDecimal("18"), BigDecimal.ZERO, "KWD", false);

        assertAmount("100.001", result.taxableAmount());
        assertAmount("18.000", result.taxAmount());
        assertAmount("118.001", result.totalAmount());
        assertEquals(3, result.currencyScale());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected " + expected + " but got " + actual);
    }
}
