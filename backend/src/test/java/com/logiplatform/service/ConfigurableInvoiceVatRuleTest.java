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

/**
 * Statutory Rwanda defaults must not be overridden by custom tenant
 * percentages.
 */
class ConfigurableInvoiceVatRuleTest {
    private final UUID tenantId = UUID.randomUUID();
    private final LocalDate issueDate = LocalDate.of(2026, 11, 11);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void vatRegisteredRwandaCompanyUsesStatutoryEighteenPercentNotHistoricalCustomRate() {
        TenantContext.setTenantId(tenantId);
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForList(anyString(), eq(tenantId)))
                .thenReturn(List.of(Map.of("jurisdiction_code", "RW", "country_code", "RW",
                        "vat_registration_status", "REGISTERED")));
        when(database.queryForList(anyString(), eq(tenantId), eq("RW"),
                eq(RwandaInvoiceTaxPolicy.STANDARD), eq(issueDate), eq(issueDate)))
                .thenReturn(List.of(Map.of("jurisdiction_code", "RW",
                        "code", RwandaInvoiceTaxPolicy.STANDARD, "rate", new BigDecimal("18.0000"),
                        "inclusive", false)));
        FinancialHardeningService service = new FinancialHardeningService(database, null, null, null, null);
        Map<String, Object> selected = service.defaultInvoiceVatRule(issueDate, "RWF");
        assertEquals(0, new BigDecimal("18").compareTo((BigDecimal) selected.get("rate")));
        assertEquals(RwandaInvoiceTaxPolicy.STANDARD, selected.get("code"));
    }

    @Test
    void nonVatRegisteredRwandaCompanyCannotDefaultToEighteenPercent() {
        TenantContext.setTenantId(tenantId);
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForList(anyString(), eq(tenantId)))
                .thenReturn(List.of(Map.of("jurisdiction_code", "RW", "country_code", "RW",
                        "vat_registration_status", "NOT_REGISTERED")));
        when(database.queryForList(anyString(), eq(tenantId), eq("RW"),
                eq(RwandaInvoiceTaxPolicy.NONVAT), eq(issueDate), eq(issueDate)))
                .thenReturn(List.of(Map.of("jurisdiction_code", "RW",
                        "code", RwandaInvoiceTaxPolicy.NONVAT, "rate", BigDecimal.ZERO,
                        "inclusive", false)));
        FinancialHardeningService service = new FinancialHardeningService(database, null, null, null, null);
        assertEquals(RwandaInvoiceTaxPolicy.NONVAT,
                service.defaultInvoiceVatRule(issueDate, "RWF").get("code"));
    }

    @Test
    void unverifiedVatRegistrationBlocksInvoicing() {
        TenantContext.setTenantId(tenantId);
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForList(anyString(), eq(tenantId)))
                .thenReturn(List.of(Map.of("jurisdiction_code", "RW", "country_code", "RW",
                        "vat_registration_status", "UNVERIFIED")));
        FinancialHardeningService service = new FinancialHardeningService(database, null, null, null, null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.defaultInvoiceVatRule(issueDate, "RWF"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }

    @Test
    void missingCompanySetupStillBlocksInvoicing() {
        TenantContext.setTenantId(tenantId);
        JdbcTemplate database = mock(JdbcTemplate.class);
        when(database.queryForList(anyString(), eq(tenantId))).thenReturn(List.of());
        FinancialHardeningService service = new FinancialHardeningService(database, null, null, null, null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.defaultInvoiceVatRule(issueDate, "RWF"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }
}
