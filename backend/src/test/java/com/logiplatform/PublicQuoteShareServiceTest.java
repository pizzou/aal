package com.logiplatform;

import com.logiplatform.dto.PublicQuoteDtos.QuoteView;
import com.logiplatform.model.CommercialQuote;
import com.logiplatform.repository.CommercialQuoteRepository;
import com.logiplatform.service.PublicQuoteShareService;
import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PublicQuoteShareServiceTest extends TenantTestSupport {

    @Autowired
    private CommercialQuoteRepository quoteRepository;

    @Autowired
    private PublicQuoteShareService service;

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void publicQuoteCanBeViewedWithoutTenantContext() {
        UUID tenantId = UUID.randomUUID();
        String token = "quote-token-view-001";
        createQuoteShare(tenantId, token, "Q-1001", "OPEN");

        TenantContext.clear();
        QuoteView view = service.view(token);

        assertEquals("Q-1001", view.quoteReference());
        assertEquals("AAL Customer", view.client());
        assertEquals("Kigali → Nairobi", view.route());
        assertTrue(view.actionable());
    }

    @Test
    void invalidPublicQuoteTokenReturns404() {
        TenantContext.clear();

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.view("does-not-exist"));

        assertEquals(404, ex.getStatusCode().value());
    }


    @Test
    void duplicateResponseIsIdempotentForTheSameShare() {
        UUID tenantId = UUID.randomUUID();
        String token = "quote-token-idempotent-001";
        createQuoteShare(tenantId, token, "Q-1003", "OPEN");

        TenantContext.clear();
        assertEquals("ACCEPTED", service.respond(token, "ACCEPTED").status());
        var second = service.respond(token, "DECLINED");

        assertEquals("ACCEPTED", second.status());
        assertEquals("WON", authJdbcTemplate.queryForObject(
                "SELECT status FROM commercial_quotes WHERE quote_id=? AND tenant_id=?",
                String.class, "Q-1003", tenantId));
    }

    @Test
    void publicAcceptanceUpdatesOnlyTheSharedQuote() {
        UUID tenantId = UUID.randomUUID();
        String token = "quote-token-accept-001";
        createQuoteShare(tenantId, token, "Q-1002", "OPEN");

        TenantContext.clear();
        var result = service.respond(token, "ACCEPTED");

        assertEquals("ACCEPTED", result.status());

        String status = authJdbcTemplate.queryForObject(
                "SELECT status FROM commercial_quotes WHERE quote_id=? AND tenant_id=?",
                String.class,
                "Q-1002",
                tenantId);
        assertEquals("WON", status);
    }

    private void createQuoteShare(
            UUID tenantId,
            String rawToken,
            String quoteReference,
            String status) {

        setTenant(tenantId);

        CommercialQuote quote = new CommercialQuote(
                tenantId,
                quoteReference,
                LocalDate.now(),
                "AAL Customer",
                "Kigali → Nairobi",
                "Air Freight",
                "General Cargo",
                new BigDecimal("100.000"),
                new BigDecimal("500.0000"),
                new BigDecimal("50.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("700.0000"),
                new BigDecimal("150.0000"),
                LocalDate.now().plusDays(7),
                status,
                "Test Owner",
                LocalDate.now().plusDays(1),
                "Integration test quote",
                "RULES_BASED");

        CommercialQuote saved = quoteRepository.saveAndFlush(quote);
        String tokenHash = sha256(rawToken);

        authJdbcTemplate.update(
                "INSERT INTO commercial_quote_shares "
                        + "(id,tenant_id,quote_id,token_hash,recipient_email,expires_at) "
                        + "VALUES(?,?,?,?,?,?)",
                UUID.randomUUID(),
                tenantId,
                saved.getId(),
                tokenHash,
                "customer@example.com",
                Instant.now().plusSeconds(3600));
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
