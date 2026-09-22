package com.logiplatform.service;

import com.logiplatform.dto.CommercialDtos.QuoteRequest;
import com.logiplatform.dto.PublicCommercialDtos.PublicQuoteOption;
import com.logiplatform.dto.PublicCommercialDtos.PublicQuoteRequestResponse;
import com.logiplatform.repository.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import static com.logiplatform.controller.PublicCommercialController.PublicQuoteRequest;

@Service
public class PublicQuoteRequestService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${app.frontend.url:https://portal.africalogisticaviation.com}")
    private String frontendUrl;

    private final JdbcTemplate publicDb;
    private final JdbcTemplate tenantDb;
    private final RateEngineService rates;
    private final CommercialOperationsService commercial;
    private final MailService mail;

    public PublicQuoteRequestService(
            @Qualifier("publicJdbcTemplate") JdbcTemplate publicDb,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate tenantDb,
            RateEngineService rates,
            CommercialOperationsService commercial,
            MailService mail) {
        this.publicDb = publicDb;
        this.tenantDb = tenantDb;
        this.rates = rates;
        this.commercial = commercial;
        this.mail = mail;
    }

    @Transactional
    public PublicQuoteRequestResponse create(PublicQuoteRequest request) {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) {
            throw new IllegalStateException("Tenant context is required");
        }

        String quoteReference = "WEB-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);

        String route = safe(request.origin()) + " → " + safe(request.destination());
        BigDecimal weight = effectiveChargeableWeight(request);
        LocalDate validUntil = LocalDate.now().plusDays(7);

        String notes = "PUBLIC WEB REQUEST | Email: " + safe(request.email())
                + " | Phone: " + safe(request.phone())
                + " | Packages: " + (request.packages() == null ? "-" : request.packages())
                + " | Volume CBM: " + (request.volumeCbm() == null ? "-" : request.volumeCbm())
                + (request.notes() == null || request.notes().isBlank()
                        ? ""
                        : " | " + request.notes().trim());

        var createdQuote = commercial.createQuote(new QuoteRequest(
                quoteReference,
                LocalDate.now(),
                firstNonBlank(request.company(), request.contactName(), request.email()),
                route,
                request.serviceType(),
                request.commodity(),
                weight,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                validUntil,
                "REQUESTED",
                "WEB",
                LocalDate.now().plusDays(2),
                notes,
                "PUBLIC_REQUEST",
                request.email(),
                request.contactName(),
                request.phone(),
                "USD",
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null));

        UUID requestId = UUID.randomUUID();
        String rawToken = generateToken();
        String tokenHash = hash(rawToken);
        Instant expiresAt = Instant.now().plusSeconds(7L * 24L * 3600L);

        tenantDb.update(
                """
                INSERT INTO public_quote_requests(
                    id, tenant_id, token_hash, quote_id, origin, destination,
                    requested_mode, commodity, chargeable_weight_kg, volume_cbm,
                    packages, company, contact_name, email, phone, notes,
                    expires_at, created_at, status
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),'REQUESTED')
                """,
                requestId,
                tenant,
                tokenHash,
                createdQuote.id(),
                safe(request.origin()),
                safe(request.destination()),
                request.serviceType() == null
                        ? "ALL"
                        : request.serviceType().trim().toUpperCase(Locale.ROOT),
                request.commodity(),
                weight,
                request.volumeCbm(),
                request.packages(),
                request.company(),
                request.contactName(),
                request.email() == null ? null : request.email().trim().toLowerCase(Locale.ROOT),
                request.phone(),
                notes,
                expiresAt);

        List<PublicQuoteOption> options = preview(tenant, request, validUntil);

        String resultUrl = frontendUrl.replaceAll("/$", "") + "/quote/results/" + rawToken;

        afterCommit(() -> {
            if (options.isEmpty()) {
                mail.sendQuoteRequestReceived(
                        request.email(),
                        request.contactName(),
                        createdQuote.quoteId(),
                        route,
                        request.serviceType());
            } else {
                mail.sendPublicQuoteResult(
                        request.email(),
                        request.contactName(),
                        createdQuote.quoteId(),
                        route,
                        options,
                        resultUrl);
            }
        });

        return new PublicQuoteRequestResponse(
                rawToken,
                createdQuote.quoteId(),
                Instant.now(),
                validUntil,
                request.origin() == null ? null : request.origin().trim(),
                request.destination() == null ? null : request.destination().trim(),
                request.company(),
                request.contactName(),
                request.email(),
                request.phone(),
                request.commodity(),
                request.packages(),
                request.volumeCbm(),
                options);
    }

    @Transactional(readOnly = true)
    public PublicQuoteRequestResponse view(String token) {
        RequestRow row = find(token);

        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Quote request is invalid or expired");
        }

        PublicQuoteRequest input = new PublicQuoteRequest(
                row.origin(),
                row.destination(),
                row.requestedMode(),
                row.commodity(),
                row.weight(),
                row.volumeCbm(),
                row.packages(),
                row.company(),
                row.contactName(),
                row.email(),
                row.phone(),
                row.notes());

        return new PublicQuoteRequestResponse(
                token,
                row.quoteReference(),
                row.createdAt(),
                toLocalDate(row.validUntil()),
                row.origin(),
                row.destination(),
                row.company(),
                row.contactName(),
                row.email(),
                row.phone(),
                row.commodity(),
                row.packages(),
                row.volumeCbm(),
                preview(row.tenantId(), input, toLocalDate(row.validUntil())));
    }

    private List<PublicQuoteOption> preview(
            UUID tenant,
            PublicQuoteRequest request,
            LocalDate validUntil) {

        List<PublicQuoteOption> options = new ArrayList<>();

        String requestedMode = request.serviceType() == null
                ? "ALL"
                : request.serviceType().trim().toUpperCase(Locale.ROOT);

        List<String> modes =
                requestedMode.isBlank()
                        || requestedMode.equals("ALL")
                        || requestedMode.equals("ALL MODES")
                        ? List.of("AIR", "SEA", "ROAD", "RAIL")
                        : List.of(normalizeMode(requestedMode));

        UUID previous = TenantContext.isSet()
                ? TenantContext.getTenantId()
                : null;
        TenantContext.setTenantId(tenant);

        try {
            for (String mode : modes) {
                try {
                    Map<String,Object> quote = rates.advancedPublicQuote(
                            tenant,
                            new com.logiplatform.dto.AdvancedLogisticsDtos.RatePreviewRequest(
                                    mode,
                                    request.origin(),
                                    request.destination(),
                                    laneCode(request.origin(), request.destination()),
                                    null,
                                    null,
                                    effectiveChargeableWeight(request).max(BigDecimal.ONE),
                                    request.volumeCbm(),
                                    request.commodity(),
                                    "USD",
                                    List.of()));

                    options.add(new PublicQuoteOption(
                            mode,
                            label(mode),
                            decimal(quote.get("baseCharge")),
                            decimal(quote.get("fuelSurcharge")),
                            decimal(quote.get("totalCharge")),
                            String.valueOf(quote.getOrDefault("currency", "USD")),
                            String.valueOf(quote.getOrDefault("pricingSource", "RULES_BASED")),
                            !Boolean.TRUE.equals(quote.get("marginWarning")),
                            validUntil));
                } catch (ResponseStatusException ex) {
                    if (ex.getStatusCode() != HttpStatus.NOT_FOUND) throw ex;
                    // No public rate card configured for this mode.
                    // The customer can still request a formal quotation.
                }
            }
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }

        return List.copyOf(options);
    }

    @Transactional
    public BookingContext prepareBooking(String token, String selectedMode) {
        RequestRow row = find(token);

        if (row == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Quote request is invalid or expired");
        }

        if ("BOOKED".equalsIgnoreCase(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quote request has already been booked.");
        }

        int claimed = tenantDb.update(
                """
                UPDATE public_quote_requests
                   SET status='BOOKING',
                       booking_claimed_at=now()
                 WHERE token_hash=?
                   AND expires_at>now()
                   AND booked_shipment_id IS NULL
                   AND (
                        status='REQUESTED'
                        OR (
                            status='BOOKING'
                            AND booking_claimed_at < now() - interval '10 minutes'
                        )
                   )
                """,
                hash(token));

        if (claimed != 1) {
            RequestRow latest = find(token);
            if (latest == null) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Quote request is invalid or expired");
            }
            if ("BOOKED".equalsIgnoreCase(latest.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This quote request has already been booked.");
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This quote request is already being booked. Please retry shortly.");
        }

        row = find(token);
        if (row == null || !"BOOKING".equalsIgnoreCase(row.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The quote booking could not be claimed. Please retry.");
        }

        String mode = normalizeMode(
                selectedMode == null || selectedMode.isBlank()
                        ? row.requestedMode()
                        : selectedMode);

        PublicQuoteRequest input = new PublicQuoteRequest(
                row.origin(),
                row.destination(),
                row.requestedMode(),
                row.commodity(),
                row.weight(),
                row.volumeCbm(),
                row.packages(),
                row.company(),
                row.contactName(),
                row.email(),
                row.phone(),
                row.notes());

        BigDecimal quotedAmount = null;
        String currency = "USD";

        for (PublicQuoteOption option :
                preview(row.tenantId(), input, toLocalDate(row.validUntil()))) {
            if (mode.equals(option.mode())) {
                quotedAmount = option.totalCharge();
                currency = option.currency();
                break;
            }
        }

        return new BookingContext(
                row.id(),
                row.tenantId(),
                row.origin(),
                row.destination(),
                row.company(),
                row.contactName(),
                row.email(),
                row.phone(),
                row.commodity(),
                row.packages(),
                mode,
                quotedAmount,
                currency);
    }

    @Transactional
    public void markBooked(String token, UUID shipmentId) {
        if (token == null || token.isBlank() || shipmentId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Booking token and shipment id are required");
        }

        int updated = tenantDb.update(
                """
                UPDATE public_quote_requests
                   SET status='BOOKED',
                       booked_shipment_id=?,
                       booked_at=now(),
                       booking_claimed_at=NULL
                 WHERE token_hash=?
                   AND status='BOOKING'
                   AND booked_shipment_id IS NULL
                """,
                shipmentId,
                hash(token));

        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Booking claim was lost or the quote was already booked.");
        }
    }

    @Transactional
    public void releaseBookingClaim(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        tenantDb.update(
                """
                UPDATE public_quote_requests
                   SET status='REQUESTED',
                       booking_claimed_at=NULL
                 WHERE token_hash=?
                   AND status='BOOKING'
                   AND booked_shipment_id IS NULL
                """,
                hash(token));
    }

    private RequestRow find(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return null;
        }

        String tokenHash = hash(token);

        try {
            return publicDb.queryForObject(
                    """
                    SELECT
                        p.id,
                        p.tenant_id,
                        p.quote_id,
                        q.quote_id AS quote_reference,
                        p.origin,
                        p.destination,
                        p.requested_mode,
                        p.commodity,
                        p.chargeable_weight_kg,
                        p.volume_cbm,
                        p.packages,
                        p.company,
                        p.contact_name,
                        p.email,
                        p.phone,
                        p.notes,
                        p.expires_at,
                        p.created_at,
                        p.status
                    FROM public_quote_requests p
                    JOIN commercial_quotes q
                      ON q.id = p.quote_id
                     AND q.tenant_id = p.tenant_id
                    WHERE p.token_hash = ?
                      AND p.expires_at > now()
                    """,
                    (rs, n) -> new RequestRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("quote_id", UUID.class),
                            rs.getString("quote_reference"),
                            rs.getString("origin"),
                            rs.getString("destination"),
                            rs.getString("requested_mode"),
                            rs.getString("commodity"),
                            rs.getBigDecimal("chargeable_weight_kg"),
                            rs.getBigDecimal("volume_cbm"),
                            (Integer) rs.getObject("packages"),
                            rs.getString("company"),
                            rs.getString("contact_name"),
                            rs.getString("email"),
                            rs.getString("phone"),
                            rs.getString("notes"),
                            timestampToInstant(rs.getTimestamp("expires_at")),
                            timestampToInstant(rs.getTimestamp("created_at")),
                            rs.getString("status")),
                    tokenHash);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }


    private static void afterCommit(Runnable callback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }
    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to create public quote token",
                    e);
        }
    }

    private static BigDecimal effectiveChargeableWeight(
            PublicQuoteRequest request) {

        BigDecimal actual = nz(request.chargeableWeightKg());

        BigDecimal volumetric =
                nz(request.volumeCbm())
                        .multiply(BigDecimal.valueOf(167));

        String mode =
                request.serviceType() == null
                        ? "ALL"
                        : request.serviceType()
                                .trim()
                                .toUpperCase(Locale.ROOT);

        if (mode.equals("AIR")
                || mode.equals("ALL")
                || mode.equals("ALL MODES")) {
            return actual.max(volumetric);
        }

        return actual;
    }

    private static Instant timestampToInstant(
            java.sql.Timestamp timestamp) {
        return timestamp == null
                ? Instant.now()
                : timestamp.toInstant();
    }

    private static LocalDate toLocalDate(Instant value) {
        return value == null
                ? LocalDate.now()
                : value.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String normalizeMode(String raw) {
        String value =
                raw == null
                        ? "ROAD"
                        : raw.trim().toUpperCase(Locale.ROOT);

        return switch (value) {
            case "AIR", "AIRFREIGHT", "AIR FREIGHT" -> "AIR";
            case "SEA", "SEA FREIGHT", "SEAFREIGHT" -> "SEA";
            case "ROAD", "ROAD FREIGHT", "ROADFREIGHT" -> "ROAD";
            case "RAIL", "RAIL FREIGHT", "RAILFREIGHT" -> "RAIL";
            case "ALL", "ALL MODES" -> "AIR";
            default -> "ROAD";
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static String laneCode(String origin, String destination) {
        String o = origin == null ? "" : origin.trim().toUpperCase(Locale.ROOT);
        String d = destination == null ? "" : destination.trim().toUpperCase(Locale.ROOT);
        if (o.isBlank() || d.isBlank()) return null;
        return o + "-" + d;
    }

    private static String label(String mode) {
        return switch (mode) {
            case "AIR" -> "Air freight";
            case "SEA" -> "Sea freight";
            case "ROAD" -> "Road freight";
            case "RAIL" -> "Rail freight";
            default -> mode;
        };
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    private static String safe(String value) {
        return value == null || value.isBlank()
                ? "-"
                : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return "Web customer";
    }

    public record BookingContext(
            UUID requestId,
            UUID tenantId,
            String origin,
            String destination,
            String company,
            String contactName,
            String email,
            String phone,
            String commodity,
            Integer packages,
            String mode,
            BigDecimal quotedAmount,
            String currency) {
    }

    private record RequestRow(
            UUID id,
            UUID tenantId,
            UUID quoteId,
            String quoteReference,
            String origin,
            String destination,
            String requestedMode,
            String commodity,
            BigDecimal weight,
            BigDecimal volumeCbm,
            Integer packages,
            String company,
            String contactName,
            String email,
            String phone,
            String notes,
            Instant validUntil,
            Instant createdAt,
            String status) {
    }
}
