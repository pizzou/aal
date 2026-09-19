package com.logiplatform.service;

import com.logiplatform.dto.PublicQuoteDtos.QuoteResponseAction;
import com.logiplatform.dto.PublicQuoteDtos.QuoteView;
import com.logiplatform.model.CommercialQuote;
import com.logiplatform.repository.ClientRecordRepository;
import com.logiplatform.repository.CommercialQuoteRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PublicQuoteShareService {

    private final CommercialQuoteRepository quotes;
    private final ClientRecordRepository clients;
    private final JdbcTemplate db;
    private final MailService mail;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.frontend.url:https://aal-a.vercel.app}")
    private String frontendUrl;

    public PublicQuoteShareService(
            CommercialQuoteRepository quotes,
            ClientRecordRepository clients,
            JdbcTemplate db,
            MailService mail) {
        this.quotes = quotes;
        this.clients = clients;
        this.db = db;
        this.mail = mail;
    }

    @Transactional
    public QuoteShareResult share(UUID quoteId, String overrideEmail) {
        UUID tenant = TenantContext.getTenantId();

        CommercialQuote quote = quotes.findById(quoteId)
                .filter(q -> tenant.equals(q.getTenantId()))
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Quote not found"));

        LocalDate today = LocalDate.now();

        if (quote.getValidUntil() != null
                && quote.getValidUntil().isBefore(today)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quotation has expired. Renew it before sharing.");
        }

        if ("LOST".equalsIgnoreCase(quote.getStatus())
                || "EXPIRED".equalsIgnoreCase(quote.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quotation is no longer shareable.");
        }

        var client = clients
                .findFirstByTenantIdAndClientCompanyIgnoreCase(
                        tenant,
                        quote.getClient())
                .orElse(null);

        String email =
                overrideEmail == null || overrideEmail.isBlank()
                        ? (client == null ? null : client.getEmail())
                        : overrideEmail.trim();

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Customer email is not configured. "
                            + "Add the customer's email or provide a recipient email.");
        }

        String name = client == null
                ? quote.getClient()
                : client.getContactPerson();

        String rawToken = generateToken();
        String tokenHash = hash(rawToken);

        Instant expiresAt =
                quote.getValidUntil() == null
                        ? Instant.now().plusSeconds(30L * 24L * 3600L)
                        : quote.getValidUntil()
                                .plusDays(1)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC);

        UUID shareId = UUID.randomUUID();

        db.update(
                """
                INSERT INTO commercial_quote_shares
                    (id, tenant_id, quote_id, token_hash, recipient_email, expires_at)
                VALUES
                    (?, ?, ?, ?, ?, ?)
                """,
                shareId,
                tenant,
                quoteId,
                tokenHash,
                email,
                expiresAt
        );

        String url =
                frontendUrl.replaceAll("/$", "")
                        + "/quote/view/"
                        + rawToken;

        mail.sendQuotationShare(
                email,
                name,
                quote.getQuoteId(),
                url,
                quote.getRoute(),
                quote.getServiceType(),
                quote.getQuotedAmount() == null
                        ? "—"
                        : quote.getQuotedAmount()
                                .stripTrailingZeros()
                                .toPlainString(),
                quote.getValidUntil() == null
                        ? "30 days"
                        : quote.getValidUntil().toString()
        );

        return new QuoteShareResult(
                shareId,
                quote.getQuoteId(),
                email,
                url,
                expiresAt
        );
    }

    @Transactional
    public QuoteView view(String token) {
        ShareRow row = find(token);

        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Quotation link is invalid or expired");
        }

        db.update(
                """
                UPDATE commercial_quote_shares
                SET opened_at = COALESCE(opened_at, now())
                WHERE id = ?
                """,
                row.id()
        );

        return toView(row);
    }

    @Transactional
    public QuoteResponseAction respond(
            String token,
            String action) {

        ShareRow row = find(token);

        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Quotation link is invalid or expired");
        }

        CommercialQuote quote = row.quote();

        String normalized =
                action == null
                        ? ""
                        : action.trim().toUpperCase();

        if (!normalized.equals("ACCEPTED")
                && !normalized.equals("DECLINED")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Action must be ACCEPTED or DECLINED");
        }

        if (quote.getValidUntil() != null
                && quote.getValidUntil().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quotation has expired.");
        }

        if (row.response() != null
                && !row.response().isBlank()) {
            return new QuoteResponseAction(
                    row.response(),
                    "This quotation has already received your response."
            );
        }

        if ("ACCEPTED".equals(normalized)) {
            quote.changeStatus("WON");
        } else {
            quote.changeStatus("LOST");
        }

        quotes.save(quote);

        db.update(
                """
                UPDATE commercial_quote_shares
                SET response = ?, responded_at = now()
                WHERE id = ?
                """,
                normalized,
                row.id()
        );

        return new QuoteResponseAction(
                normalized,
                "Your response has been recorded. AAL will follow up with you."
        );
    }

    private ShareRow find(String token) {
        if (token == null
                || token.isBlank()
                || token.length() > 200) {
            return null;
        }

        String tokenHash = hash(token);

        var rows = db.query(
                """
                SELECT
                    s.id,
                    s.response,
                    q.id AS quote_id
                FROM commercial_quote_shares s
                JOIN commercial_quotes q
                  ON q.id = s.quote_id
                 AND q.tenant_id = s.tenant_id
                WHERE s.token_hash = ?
                  AND s.expires_at > now()
                """,
                (rs, rowNum) ->
                        new ShareLookupRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("quote_id", UUID.class),
                                rs.getString("response")
                        ),
                tokenHash
        );

        if (rows.isEmpty()) {
            return null;
        }

        ShareLookupRow lookup = rows.get(0);

        CommercialQuote quote =
                quotes.findById(lookup.quoteId()).orElse(null);

        if (quote == null) {
            return null;
        }

        return new ShareRow(
                lookup.id(),
                lookup.quoteId(),
                lookup.response(),
                quote
        );
    }

    private QuoteView toView(ShareRow row) {
        CommercialQuote quote = row.quote();

        LocalDate today = LocalDate.now();

        boolean valid =
                quote.getValidUntil() == null
                        || !quote.getValidUntil().isBefore(today);

        boolean actionable =
                valid
                        && !"WON".equalsIgnoreCase(quote.getStatus())
                        && !"LOST".equalsIgnoreCase(quote.getStatus())
                        && !"EXPIRED".equalsIgnoreCase(quote.getStatus());

        return new QuoteView(
                row.id(),
                quote.getQuoteId(),
                quote.getQuoteDate(),
                quote.getClient(),
                quote.getRoute(),
                quote.getServiceType(),
                quote.getCommodity(),
                quote.getChargeableWeightKg(),
                quote.getQuotedAmount(),
                quote.getValidUntil(),
                quote.getStatus(),
                actionable,
                row.response()
        );
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            raw.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create secure quotation token",
                    e
            );
        }
    }

    public record QuoteShareResult(
            UUID shareId,
            String quoteReference,
            String recipientEmail,
            String url,
            Instant expiresAt) {
    }

    private record ShareLookupRow(
            UUID id,
            UUID quoteId,
            String response) {
    }

    private record ShareRow(
            UUID id,
            UUID quoteId,
            String response,
            CommercialQuote quote) {
    }
}