package com.logiplatform;

import com.logiplatform.model.Shipment;
import com.logiplatform.model.TransportMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShipmentFinancialRulesTest {
    @Test
    void reproducesAalWorkbookFinancialRules() {
        Shipment shipment = new Shipment(UUID.randomUUID(), "AAL-001", "KGL", "NBO", TransportMode.AIR, "Carrier", "AWB-001");
        shipment.updateCommandCenterFields(
                "Client", null, "Cargo", "RW", "KGL", "KE", "NBO",
                bd("120"), bd("150"), 3, "Carrier", "AIR", "Operator",
                bd("400"), bd("50"), bd("1000"), bd("250"), bd("300"), bd("20"),
                "Partially Paid", "Owner", "INV-001", Instant.now(), Instant.now().plusSeconds(86400),
                null, null, null, "USD");

        assertEquals(bd("150"), shipment.getChargeableWeightKg());
        assertEquals(bd("450"), shipment.getTotalCost());
        assertEquals(bd("550"), shipment.getGrossProfit());
        assertEquals(bd("55.0000"), shipment.getMarginPercent());
        assertEquals(bd("750"), shipment.getAmountRemaining());
    }

    @Test
    void pricingWorkflowUpdatesOnlyCommercialTotalsAndPaymentStatus() {
        Shipment shipment = new Shipment(UUID.randomUUID(), "AAL-002", "KGL", "NBO", TransportMode.AIR, "Carrier", null);
        shipment.updateCommercialPricing(bd("1500"), bd("400"), bd("50"), bd("25"), "rwf");
        assertEquals(bd("1500"), shipment.getAmountBilledToClient());
        assertEquals(bd("450"), shipment.getTotalCost());
        assertEquals(bd("1050"), shipment.getGrossProfit());
        assertEquals("RWF", shipment.getCurrency());
        assertEquals("Outstanding", shipment.getPaymentStatus());
    }

    @Test
    void pricingCannotReduceRevenueBelowAlreadyCollectedCustomerPayments() {
        Shipment shipment = new Shipment(UUID.randomUUID(), "AAL-003", "KGL", "NBO", TransportMode.AIR, "Carrier", null);
        shipment.updateCommandCenterFields(
                "Client", null, "Cargo", "RW", "KGL", "KE", "NBO",
                bd("120"), bd("150"), 3, "Carrier", "AIR", "Operator",
                bd("400"), bd("50"), bd("1000"), bd("250"), bd("0"), bd("20"),
                "Partially Paid", "Owner", null, null, null, null, null, null, "USD");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> shipment.updateCommercialPricing(bd("200"), bd("400"), bd("50"), bd("20"), "USD"));
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
