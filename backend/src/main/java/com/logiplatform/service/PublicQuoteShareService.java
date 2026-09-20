package com.logiplatform.service;

import com.logiplatform.dto.PublicQuoteDtos.QuoteResponseAction;
import com.logiplatform.dto.PublicQuoteDtos.QuoteView;
import com.logiplatform.model.CommercialQuote;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.ClientRecordRepository;
import com.logiplatform.repository.CommercialQuoteRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final CommercialOperationsService commercialOperations;
    private final ShipmentRepository shipments;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.frontend.url:https://portal.africalogisticaviation.com}")
    private String frontendUrl;

    public PublicQuoteShareService(
            CommercialQuoteRepository quotes,
            ClientRecordRepository clients,
            @Qualifier("publicJdbcTemplate") JdbcTemplate db,
            MailService mail,
            CommercialOperationsService commercialOperations,
            ShipmentRepository shipments) {
        this.quotes = quotes;
        this.clients = clients;
        this.db = db;
        this.mail = mail;
        this.commercialOperations = commercialOperations;
        this.shipments = shipments;
    }

    /**
     * Internal authenticated operation: tenant context is present here and the
     * normal tenant-aware JPA repositories are intentionally used.
     */
    @Transactional
    public QuoteShareResult share(UUID quoteId, String overrideEmail) {
        UUID tenant = TenantContext.getTenantId();

        CommercialQuote quote = quotes.findById(quoteId)
                .filter(q -> tenant.equals(q.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Quote not found"));

        LocalDate today = LocalDate.now();

        if (quote.getValidUntil() != null && quote.getValidUntil().isBefore(today)) {
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

        var client = clients.findFirstByTenantIdAndClientCompanyIgnoreCase(
                tenant,
                quote.getClient()).orElse(null);

        String email = overrideEmail == null || overrideEmail.isBlank()
                ? (client != null && client.getEmail() != null && !client.getEmail().isBlank()
                    ? client.getEmail()
                    : quote.getCustomerEmail())
                : overrideEmail.trim();

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Customer email is not configured. Add the customer's email or provide a recipient email.");
        }

        String name = client != null && client.getContactPerson() != null && !client.getContactPerson().isBlank()
                ? client.getContactPerson()
                : (quote.getCustomerContactName() == null ? quote.getClient() : quote.getCustomerContactName());
        String rawToken = generateToken();
        String tokenHash = hash(rawToken);
        Instant expiresAt = quote.getValidUntil() == null
                ? Instant.now().plusSeconds(30L * 24L * 3600L)
                : quote.getValidUntil().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        UUID shareId = UUID.randomUUID();

        db.update(
                "INSERT INTO commercial_quote_shares "
                        + "(id,tenant_id,quote_id,token_hash,recipient_email,expires_at) "
                        + "VALUES(?,?,?,?,?,?)",
                shareId, tenant, quoteId, tokenHash, email, expiresAt);

        String url = frontendUrl.replaceAll("/$", "") + "/quote/view/" + rawToken;

        mail.sendQuotationShare(
                email,
                name,
                quote.getQuoteId(),
                url,
                quote.getRoute(),
                quote.getServiceType(),
                quote.getQuotedAmount() == null
                        ? "—"
                        : quote.getQuotedAmount().stripTrailingZeros().toPlainString(),
                quote.getValidUntil() == null ? "30 days" : quote.getValidUntil().toString());

        return new QuoteShareResult(shareId, quote.getQuoteId(), email, url, expiresAt);
    }

    /**
     * Public endpoint: deliberately independent of TenantContext. The raw auth
     * datasource is used, and every query is constrained by the hashed token.
     */
    @Transactional(transactionManager = "publicTransactionManager")
    public QuoteView view(String token) {
        ShareRow row = find(token, false);
        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Quotation link is invalid or expired");
        }

        db.update(
                "UPDATE commercial_quote_shares "
                        + "SET opened_at=COALESCE(opened_at,now()) WHERE id=?",
                row.id());

        return toView(row);
    }

    /**
     * Public endpoint: accepts or declines the quotation without requiring a
     * login or tenant context. The update is explicitly scoped by tenant and
     * quote id so it cannot affect another quotation.
     */
    @Transactional(transactionManager = "publicTransactionManager")
    public QuoteResponseAction respond(String token, String action) {
        ShareRow row = find(token, true);
        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Quotation link is invalid or expired");
        }

        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!normalized.equals("ACCEPTED") && !normalized.equals("DECLINED")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Action must be ACCEPTED or DECLINED");
        }

        if (row.validUntil() != null && row.validUntil().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quotation has expired.");
        }

        if (row.response() != null && !row.response().isBlank()) {
            return new QuoteResponseAction(
                    row.response(),
                    "This quotation has already received your response.");
        }

        if ("WON".equalsIgnoreCase(row.status())
                || "LOST".equalsIgnoreCase(row.status())
                || "EXPIRED".equalsIgnoreCase(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quotation is no longer actionable.");
        }

        String newStatus = "ACCEPTED".equals(normalized) ? "WON" : "LOST";

        int updated = db.update(
                "UPDATE commercial_quotes SET status=? WHERE id=? AND tenant_id=?",
                newStatus,
                row.quoteId(),
                row.tenantId());

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The quotation could not be updated. Please contact AAL.");
        }

        db.update(
                "UPDATE commercial_quote_shares SET response=?,responded_at=now() WHERE id=? AND response IS NULL",
                normalized,
                row.id());

        return new QuoteResponseAction(
                normalized,
                "Your response has been recorded. AAL will follow up with you.");
    }

    @Transactional(transactionManager = "publicTransactionManager")
    public PublicBookingResult book(String token, String shipmentReference) {
        ShareRow row = find(token, true);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation link is invalid or expired");
        }
        if (row.validUntil() != null && row.validUntil().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This quotation has expired.");
        }
        if (row.bookedShipmentId() != null) {
            TenantContext.setTenantId(row.tenantId());
            try {
                Shipment existing = shipments.findByIdAndTenantId(row.bookedShipmentId(), row.tenantId()).orElse(null);
                if (existing != null) {
                    return new PublicBookingResult(existing.getId(), existing.getReferenceCode(), existing.getTrackingToken(), existing.getStatus().name(), "Booking already confirmed.");
                }
            } finally {
                TenantContext.clear();
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The quotation booking record is inconsistent.");
        }
        if (!"ACCEPTED".equalsIgnoreCase(row.response()) && !"WON".equalsIgnoreCase(row.status()) && !"CONVERTED".equalsIgnoreCase(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Accept the quotation before booking it.");
        }

        int claimed = db.update(
                "UPDATE commercial_quote_shares SET booking_claimed_at=now() "
                        + "WHERE id=? AND booked_shipment_id IS NULL AND (booking_claimed_at IS NULL "
                        + "OR booking_claimed_at < now() - interval '10 minutes')",
                row.id());
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This quotation is already being booked. Please retry shortly.");
        }

        TenantContext.setTenantId(row.tenantId());
        try {
            String reference = shipmentReference == null || shipmentReference.isBlank()
                    ? "AAL-" + row.quoteReference()
                    : shipmentReference.trim();
            UUID shipmentId;
            try {
                var converted = commercialOperations.convertQuoteToShipment(row.quoteId(), reference, null, null);
                shipmentId = converted.shipmentId();
            } catch (ResponseStatusException ex) {
                Shipment existing = shipments.findByTenantIdAndReferenceCode(row.tenantId(), reference).orElse(null);
                if (existing == null) {
                    throw ex;
                }
                shipmentId = existing.getId();
            }

            Shipment shipment = shipments.findByIdAndTenantId(shipmentId, row.tenantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Shipment was created but could not be loaded"));
            db.update("UPDATE commercial_quote_shares SET booked_shipment_id=?, booked_at=now(), booking_claimed_at=NULL WHERE id=?",
                    shipment.getId(), row.id());
            return new PublicBookingResult(shipment.getId(), shipment.getReferenceCode(), shipment.getTrackingToken(), shipment.getStatus().name(), "Booking confirmed.");
        } catch (RuntimeException ex) {
            db.update("UPDATE commercial_quote_shares SET booking_claimed_at=NULL WHERE id=? AND booked_shipment_id IS NULL",
                    row.id());
            throw ex;
        } finally {
            TenantContext.clear();
        }
    }

    private ShareRow find(String token) {
        return find(token, false);
    }

    private ShareRow find(String token, boolean forUpdate) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return null;
        }

        String tokenHash = hash(token);

        var rows = db.query(
                "SELECT "
                        + "s.id, s.tenant_id, s.quote_id, s.response, s.booked_shipment_id, "
                        + "q.quote_id AS quote_reference, q.quote_date, q.client, q.route, "
                        + "q.service_type, q.commodity, q.chargeable_weight_kg, q.quoted_amount, "
                        + "q.valid_until, q.status "
                        + "FROM commercial_quote_shares s "
                        + "JOIN commercial_quotes q "
                        + "ON q.id=s.quote_id AND q.tenant_id=s.tenant_id "
                        + "WHERE s.token_hash=? AND s.expires_at>now()"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, n) -> new ShareRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("quote_id", UUID.class),
                        rs.getString("response"),
                        rs.getObject("booked_shipment_id", UUID.class),
                        rs.getString("quote_reference"),
                        rs.getObject("quote_date", LocalDate.class),
                        rs.getString("client"),
                        rs.getString("route"),
                        rs.getString("service_type"),
                        rs.getString("commodity"),
                        rs.getBigDecimal("chargeable_weight_kg"),
                        rs.getBigDecimal("quoted_amount"),
                        rs.getObject("valid_until", LocalDate.class),
                        rs.getString("status")
                ),
                tokenHash);

        return rows.isEmpty() ? null : rows.get(0);
    }

    private QuoteView toView(ShareRow row) {
        boolean valid = row.validUntil() == null
                || !row.validUntil().isBefore(LocalDate.now());

        boolean actionable = valid
                && !"WON".equalsIgnoreCase(row.status())
                && !"LOST".equalsIgnoreCase(row.status())
                && !"EXPIRED".equalsIgnoreCase(row.status())
                && (row.response() == null || row.response().isBlank());

        return new QuoteView(
                row.id(),
                row.quoteReference(),
                row.quoteDate(),
                row.client(),
                row.route(),
                row.serviceType(),
                row.commodity(),
                row.chargeableWeightKg(),
                row.quotedAmount(),
                row.validUntil(),
                row.status(),
                actionable,
                row.response());
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create secure quotation token", e);
        }
    }

    public record PublicBookingResult(
            UUID shipmentId,
            String reference,
            UUID trackingToken,
            String status,
            String message) {
    }

    public record QuoteShareResult(
            UUID shareId,
            String quoteReference,
            String recipientEmail,
            String url,
            Instant expiresAt) {
    }

    private record ShareRow(
            UUID id,
            UUID tenantId,
            UUID quoteId,
            String response,
            UUID bookedShipmentId,
            String quoteReference,
            LocalDate quoteDate,
            String client,
            String route,
            String serviceType,
            String commodity,
            java.math.BigDecimal chargeableWeightKg,
            java.math.BigDecimal quotedAmount,
            LocalDate validUntil,
            String status) {
    }
}
