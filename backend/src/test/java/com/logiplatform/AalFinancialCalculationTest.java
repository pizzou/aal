
package com.logiplatform;

import com.logiplatform.model.Shipment;
import com.logiplatform.model.TransportMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AalFinancialCalculationTest {

    @Test
    void workbookCalculationsAreServerDerived() {
        Shipment shipment = new Shipment(
                UUID.randomUUID(),
                "AWB-001",
                "KGL",
                "NBO",
                TransportMode.AIR,
                "Carrier",
                "AWB-001");

        shipment.updateCommandCenterFields(
                "AAL Client",
                "0780000000",
                "General Cargo",
                "RWA",
                "KGL",
                "KEN",
                "NBO",
                new BigDecimal("100.000"),
                new BigDecimal("125.000"),
                5,
                "Carrier",
                "AIR",
                "Operator",
                new BigDecimal("700.00"),
                new BigDecimal("100.00"),
                new BigDecimal("1200.00"),
                new BigDecimal("300.00"),
                new BigDecimal("650.00"),
                new BigDecimal("50.00"),
                "PARTIALLY_PAID",
                "Operator",
                "INV-001",
                null,
                null,
                "Follow up",
                null,
                "Test",
                "USD");

        assertEquals(new BigDecimal("125.000"), shipment.getChargeableWeightKg());
        assertEquals(new BigDecimal("800.00"), shipment.getTotalCost());
        assertEquals(new BigDecimal("400.00"), shipment.getGrossProfit());
        assertEquals(new BigDecimal("900.00"), shipment.getAmountRemaining());

        // Margin = gross profit / client revenue * 100
        // 400 / 1200 * 100 = 33.3333%
        assertEquals(new BigDecimal("33.3333"), shipment.getMarginPercent());

        assertEquals(new BigDecimal("500.00"), shipment.getNetIncome());
    }
}
