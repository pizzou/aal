package com.logiplatform.service;

import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnterpriseSafetyControlsTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final EnterpriseCompletionService service = new EnterpriseCompletionService(
            db, "test-jwt-secret-that-is-at-least-32-bytes", "");

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void staffCannotReconfigureAnotherUsersMfa() {
        UUID tenant = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        TenantContext.setTenantId(tenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new TenantPrincipal(staff, tenant, "OPERATIONS"),
                        null, List.of(new SimpleGrantedAuthority("ROLE_OPERATIONS"))));
        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> service.setupMfa(UUID.randomUUID()));
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        verifyNoInteractions(db);
    }

    @Test
    void queuedCustomsSubmissionMustNotClaimSubmitted() {
        UUID tenant = UUID.randomUUID();
        UUID declaration = UUID.randomUUID();
        TenantContext.setTenantId(tenant);
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(
                Map.of("id", declaration, "status", "DRAFT", "hs_codes", "0901", "declared_value", new BigDecimal("100.00")),
                Map.of("declaration_id", declaration, "status", "QUEUED"));
        var result = service.submitCustoms(new com.logiplatform.dto.EnterpriseCompletionDtos.CustomsSubmissionRequest(
                declaration, "customs-idem-1", "FAKE-RELEASE"));
        assertEquals("QUEUED", result.get("status"));
        verify(db, never()).update(contains("status='SUBMITTED'"), any(Object[].class));
        verify(db, never()).update(contains("status='RELEASED'"), any(Object[].class));
    }

    @Test
    void manualReleaseIsRejectedEvenForExistingDeclaration() {
        UUID declaration = UUID.randomUUID();
        TenantContext.setTenantId(UUID.randomUUID());
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("id", declaration));
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> service.releaseCustoms(declaration));
        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void csvValueEscapesFormulaAndQuotes() {
        assertEquals("\"'=1+1\"", EnterpriseCompletionService.csvCell("=1+1"));
        assertEquals("\"invoice \"\"A\"\"\"", EnterpriseCompletionService.csvCell("invoice \"A\""));
    }
}
