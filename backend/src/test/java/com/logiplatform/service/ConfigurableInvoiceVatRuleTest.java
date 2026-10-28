package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No database or Spring context is needed: tests the configured-rule selection contract. */
class ConfigurableInvoiceVatRuleTest {
    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void invoiceRuleUsesFinanceApprovedRateInsteadOfHardcodedEighteenPercent() {
        TenantContext.setTenantId(tenantId);
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForList(anyString(), eq(tenantId)))
                .thenReturn(List.of(Map.of("jurisdiction_code", "RW")));
        when(database.queryForList(anyString(), eq(tenantId), eq("RW"),
                any(LocalDate.class), any(LocalDate.class), eq("RWF")))
                .thenReturn(List.of(Map.of(
                        "jurisdiction_code", "RW",
                        "code", "VAT-APPROVED",
                        "rate", new BigDecimal("17.5000"),
                        "currency", "RWF")));

        FinancialHardeningService service = new FinancialHardeningService(database, null, null, null, null);
        Map<String, Object> selected = service.defaultInvoiceVatRule(LocalDate.of(2026, 10, 28), "RWF");
        assertEquals(new BigDecimal("17.5000"), selected.get("rate"));
        assertEquals("VAT-APPROVED", selected.get("code"));
    }

    @Test
    void absenceOfVerifiedCompanySetupStillBlocksInvoicing() {
        TenantContext.setTenantId(tenantId);
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForList(anyString(), eq(tenantId))).thenReturn(List.of());
        FinancialHardeningService service = new FinancialHardeningService(database, null, null, null, null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.defaultInvoiceVatRule(LocalDate.of(2026, 10, 28), "RWF"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }
}
