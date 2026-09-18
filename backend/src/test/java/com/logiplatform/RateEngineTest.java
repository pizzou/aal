package com.logiplatform;

import com.logiplatform.model.*;
import com.logiplatform.dto.*;
import com.logiplatform.repository.*;
import com.logiplatform.service.*;
import com.logiplatform.controller.*;

import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;


import static org.junit.jupiter.api.Assertions.*;

/**
 * The 100kg and 5kg scenarios here reproduce EXACTLY the numbers verified against
 * real Postgres NUMERIC arithmetic before this Java was written (see
 * RLS_VERIFICATION.md: 517.50 total for 100kg, min-charge floor for 5kg).
 */
import static com.logiplatform.dto.ShipmentDtos.*;
import static com.logiplatform.dto.ReportingDtos.*;
import static com.logiplatform.dto.TmsDtos.*;
import static com.logiplatform.dto.GpsDtos.*;
import static com.logiplatform.dto.LoadPlanDtos.*;
import static com.logiplatform.dto.RatingDtos.*;
import static com.logiplatform.dto.PublicTrackingDtos.*;
import static com.logiplatform.dto.WarehouseDtos.*;
import static com.logiplatform.dto.SensorDtos.*;
import static com.logiplatform.dto.AuthDtos.*;

@SpringBootTest
@ActiveProfiles("test")

class RateEngineTest extends TenantTestSupport {

    @Autowired private RateEngineService rateEngineService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void quoteMatchesExactlyTheNumbersVerifiedAgainstRealPostgres() {
        setTenant(UUID.randomUUID());
        rateEngineService.setRateCard(new SetRateCardRequest(
                "AIR", new BigDecimal("4.50"), new BigDecimal("50.00"), new BigDecimal("15.00"), "USD"));

        QuoteResponse quote = rateEngineService.quote(new QuoteRequest("AIR", new BigDecimal("100"), null));

        assertEquals(new BigDecimal("450.00"), quote.baseCharge());
        assertEquals(new BigDecimal("67.50"), quote.fuelSurcharge());
        assertEquals(new BigDecimal("517.50"), quote.totalCharge());
        assertEquals("RULES_BASED", quote.rateType(), "Must never claim to be AI/market-driven pricing");
    }

    @Test
    void minChargeFloorAppliesForSmallShipments() {
        setTenant(UUID.randomUUID());
        rateEngineService.setRateCard(new SetRateCardRequest(
                "AIR", new BigDecimal("4.50"), new BigDecimal("50.00"), new BigDecimal("15.00"), "USD"));

        QuoteResponse quote = rateEngineService.quote(new QuoteRequest("AIR", new BigDecimal("5"), null));

        assertEquals(new BigDecimal("22.50"), quote.rawWeightCharge());
        assertEquals(new BigDecimal("50.00"), quote.baseCharge(), "Must floor to min_charge, not the raw weight charge");
    }

    @Test
    void accessorialsAddToTheTotal() {
        setTenant(UUID.randomUUID());
        rateEngineService.setRateCard(new SetRateCardRequest(
                "AIR", new BigDecimal("4.50"), new BigDecimal("50.00"), new BigDecimal("0"), "USD"));
        rateEngineService.setAccessorial(new SetAccessorialRequest(
                "DG_HANDLING", "Dangerous goods handling fee", new BigDecimal("75.00"), "USD"));

        QuoteResponse quote = rateEngineService.quote(
                new QuoteRequest("AIR", new BigDecimal("100"), List.of("DG_HANDLING")));

        assertEquals(new BigDecimal("75.00"), quote.accessorialTotal());
        assertEquals(new BigDecimal("525.00"), quote.totalCharge(), "450 base + 0 fuel + 75 accessorial");
    }

    @Test
    void quotingWithNoRateCardConfiguredIsRejectedClearly() {
        setTenant(UUID.randomUUID());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> rateEngineService.quote(new QuoteRequest("SEA", new BigDecimal("100"), null)));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void settingARateCardTwiceUpdatesRatherThanDuplicates() {
        setTenant(UUID.randomUUID());
        rateEngineService.setRateCard(new SetRateCardRequest(
                "ROAD", new BigDecimal("2.00"), new BigDecimal("20.00"), new BigDecimal("0"), "USD"));
        rateEngineService.setRateCard(new SetRateCardRequest(
                "ROAD", new BigDecimal("3.00"), new BigDecimal("25.00"), new BigDecimal("0"), "USD"));

        List<RateCardResponse> cards = rateEngineService.listRateCards();
        assertEquals(1, cards.size(), "Setting a rate card for the same mode twice must update, not duplicate");
        assertEquals(new BigDecimal("3.00"), cards.get(0).baseRatePerKg());
    }

    @Test
    void rateCardsAreTenantIsolated() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        setTenant(tenantA);
        rateEngineService.setRateCard(new SetRateCardRequest(
                "AIR", new BigDecimal("4.50"), new BigDecimal("50.00"), new BigDecimal("15.00"), "USD"));

        setTenant(tenantB);
        assertThrows(ResponseStatusException.class,
                () -> rateEngineService.quote(new QuoteRequest("AIR", new BigDecimal("100"), null)),
                "Tenant B must not see tenant A's rate card");
    }
}
