package com.logiplatform.service;

import com.logiplatform.model.AccessorialCharge;
import com.logiplatform.model.RateCard;
import com.logiplatform.repository.AccessorialChargeRepository;
import com.logiplatform.repository.RateCardRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.logiplatform.dto.AdvancedLogisticsDtos.RatePreviewRequest;
import static com.logiplatform.dto.RatingDtos.*;

/**
 * Canonical freight rating engine.
 *
 * The same deterministic calculation is used by internal rating and public quoting:
 * effective-dated sell rate -> min charge -> markup -> fuel -> security -> accessorials
 * -> discount -> tax, while carrier buy rates drive the true cost/margin side.
 */
@Service
public class RateEngineService {
    private final RateCardRepository rateCardRepository;
    private final AccessorialChargeRepository accessorialChargeRepository;
    private final JdbcTemplate tenantDb;

    public RateEngineService(
            RateCardRepository rateCardRepository,
            AccessorialChargeRepository accessorialChargeRepository,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate tenantDb) {
        this.rateCardRepository = rateCardRepository;
        this.accessorialChargeRepository = accessorialChargeRepository;
        this.tenantDb = tenantDb;
    }

    @Transactional
    public RateCardResponse setRateCard(SetRateCardRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        String mode = normalize(request.transportMode());
        LocalDate validFrom = request.validFrom() == null ? LocalDate.now() : request.validFrom();
        LocalDate validUntil = request.validUntil();
        if (validUntil != null && validUntil.isBefore(validFrom)) {
            throw bad("validUntil must be on or after validFrom");
        }

        String lane = nullable(request.laneCode());
        tenantDb.update(
                "UPDATE rate_cards SET active=false, updated_at=now() "
                        + "WHERE tenant_id=? AND upper(transport_mode)=upper(?) AND active=true "
                        + "AND client_id IS NOT DISTINCT FROM ? AND carrier_id IS NOT DISTINCT FROM ? "
                        + "AND lane_code IS NOT DISTINCT FROM ? AND valid_from=?",
                tenantId, mode, request.clientId(), request.carrierId(), lane, validFrom);

        if (validFrom.isAfter(LocalDate.now())) {
            tenantDb.update(
                    "UPDATE rate_cards SET valid_until=?, updated_at=now() "
                            + "WHERE tenant_id=? AND upper(transport_mode)=upper(?) AND active=true "
                            + "AND client_id IS NOT DISTINCT FROM ? AND carrier_id IS NOT DISTINCT FROM ? "
                            + "AND lane_code IS NOT DISTINCT FROM ? AND valid_from<? "
                            + "AND (valid_until IS NULL OR valid_until>=?)",
                    validFrom.minusDays(1), tenantId, mode, request.clientId(), request.carrierId(), lane, validFrom, validFrom);
        } else {
            tenantDb.update(
                    "UPDATE rate_cards SET active=false, valid_until=CASE WHEN valid_until IS NULL OR valid_until>=? THEN ? ELSE valid_until END, updated_at=now() "
                            + "WHERE tenant_id=? AND upper(transport_mode)=upper(?) AND active=true "
                            + "AND client_id IS NOT DISTINCT FROM ? AND carrier_id IS NOT DISTINCT FROM ? "
                            + "AND lane_code IS NOT DISTINCT FROM ? AND valid_from<? "
                            + "AND (valid_until IS NULL OR valid_until>=?)",
                    validFrom, validFrom.minusDays(1), tenantId, mode, request.clientId(), request.carrierId(), lane, validFrom, validFrom);
        }

        RateCard card = new RateCard(
                tenantId,
                mode,
                scale(request.baseRatePerKg()),
                scale(request.minCharge()),
                scale(request.fuelSurchargePercent()),
                currency(request.currency()),
                scale(request.securitySurchargePercent()),
                scale(request.markupPercent()),
                lane,
                request.clientId(),
                request.carrierId(),
                validFrom,
                validUntil);
        return RateCardResponse.from(rateCardRepository.saveAndFlush(card));
    }

    @Transactional(readOnly = true)
    public List<RateCardResponse> listRateCards() {
        return rateCardRepository.findAllByTenantId(TenantContext.getTenantId()).stream()
                .filter(RateCard::isActive)
                .sorted(Comparator.comparing(RateCard::getValidFrom, Comparator.nullsFirst(Comparator.reverseOrder())))
                .map(RateCardResponse::from)
                .toList();
    }

    @Transactional
    public AccessorialResponse setAccessorial(SetAccessorialRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        AccessorialCharge charge = new AccessorialCharge(
                tenantId, request.code(), request.description(), request.amount(), currency(request.currency()));
        return AccessorialResponse.from(accessorialChargeRepository.save(charge));
    }

    @Transactional(readOnly = true)
    public List<AccessorialResponse> listAccessorials() {
        return accessorialChargeRepository.findAllByTenantId(TenantContext.getTenantId()).stream()
                .map(AccessorialResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public QuoteResponse quote(QuoteRequest request) {
        Map<String, Object> advanced = advancedQuote(new RatePreviewRequest(
                request.transportMode(), null, null, null, null, null,
                request.weightKg(), null, null, null,
                request.accessorialCodes() == null ? List.of() : request.accessorialCodes().stream()
                        .map(code -> {
                            try {
                                return accessorialChargeRepository.findAllByTenantIdAndCodeIn(
                                        TenantContext.getTenantId(), List.of(code)).stream().findFirst().orElse(null);
                            } catch (Exception ignored) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .map(a -> new com.logiplatform.dto.AdvancedLogisticsDtos.ChargeInput(
                                a.getCode(), "ACCESSORIAL", a.getDescription(), a.getAmount(), a.getAmount(), a.getCurrency()))
                        .toList()));

        List<AccessorialResponse> applied = request.accessorialCodes() == null || request.accessorialCodes().isEmpty()
                ? List.of()
                : accessorialChargeRepository.findAllByTenantIdAndCodeIn(
                        TenantContext.getTenantId(), request.accessorialCodes()).stream()
                        .map(AccessorialResponse::from).toList();

        return new QuoteResponse(
                dec(advanced.get("rawWeightCharge")),
                dec(advanced.get("baseCharge")),
                dec(advanced.get("fuelSurcharge")),
                dec(advanced.get("accessorialTotal")),
                applied,
                dec(advanced.get("totalCharge")),
                Objects.toString(advanced.get("currency"), "USD"),
                Objects.toString(advanced.get("pricingSource"), "RULES_BASED"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> advancedQuote(RatePreviewRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        return calculate(tenantId, request);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> advancedPublicQuote(UUID tenantId, RatePreviewRequest request) {
        UUID previous = TenantContext.isSet() ? TenantContext.getTenantId() : null;
        TenantContext.setTenantId(tenantId);
        try {
            return calculate(tenantId, request);
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
        }
    }

    private Map<String, Object> calculate(UUID tenantId, RatePreviewRequest r) {
        String mode = normalize(r.mode());
        BigDecimal weight = nz(r.weightKg());
        if (weight.signum() < 0) throw bad("Weight must not be negative");
        LocalDate asOf = LocalDate.now();
        String requestedCurrency = currency(r.currency());

        Map<String, Object> source = findCustomerRateCard(tenantId, r.clientId(), r.laneCode(), mode, asOf);
        String pricingSource = "CUSTOMER_RATE_CARD";
        if (source == null) {
            source = findPricingRule(tenantId, r.clientId(), r.carrierId(), r.laneCode(), mode, weight, asOf);
            pricingSource = source == null ? "RATE_CARD" : "PRICING_RULE";
        }
        if (source == null) {
            source = findRateCard(tenantId, r.clientId(), r.carrierId(), r.laneCode(), mode, asOf);
        }
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No active rate configured for " + mode);
        }

        String sourceCurrency = currency(Objects.toString(source.get("currency"), "USD"));
        BigDecimal baseRate = dec(source.get("base_rate_per_kg"));
        BigDecimal minCharge = dec(source.get("min_charge"));
        BigDecimal fuelPercent = dec(source.get("fuel_percent"), dec(source.get("fuel_surcharge_percent")));
        BigDecimal securityPercent = dec(source.get("security_percent"), dec(source.get("security_surcharge_percent")));
        BigDecimal markupPercent = dec(source.get("markup_percent"));
        BigDecimal taxPercent = dec(source.get("tax_percent"));

        BigDecimal raw = money(baseRate.multiply(weight));
        BigDecimal base = money(raw.max(minCharge));
        BigDecimal markedBase = money(base.add(pct(base, markupPercent)));
        BigDecimal fuelAmount = money(pct(markedBase, fuelPercent));
        BigDecimal securityAmount = money(pct(markedBase, securityPercent));

        BigDecimal accessorialTotal = BigDecimal.ZERO;
        List<Map<String, Object>> charges = new ArrayList<>();
        if (r.charges() != null) {
            for (var charge : r.charges()) {
                BigDecimal sell = money(charge.amount());
                BigDecimal buy = money(charge.buyAmount() == null ? sell : charge.buyAmount());
                accessorialTotal = accessorialTotal.add(sell);
                charges.add(Map.of(
                        "code", charge.code(),
                        "category", charge.category() == null ? "ACCESSORIAL" : charge.category(),
                        "description", charge.description() == null ? charge.code() : charge.description(),
                        "sellAmount", sell,
                        "buyAmount", buy,
                        "currency", currency(charge.currency())));
            }
        }

        BigDecimal subtotal = money(markedBase.add(fuelAmount).add(securityAmount).add(accessorialTotal));
        Map<String, Object> discount = findDiscount(tenantId, r.clientId(), r.laneCode(), mode, asOf);
        BigDecimal discountPercent = BigDecimal.ZERO;
        if (discount != null) {
            BigDecimal qualifyingMinimum = dec(discount.get("min_charge"));
            if (subtotal.compareTo(qualifyingMinimum) >= 0) {
                discountPercent = dec(discount.get("discount_percent"));
            }
        }
        BigDecimal discountAmount = money(pct(subtotal, discountPercent));
        BigDecimal discountedSubtotal = money(subtotal.subtract(discountAmount).max(BigDecimal.ZERO));
        BigDecimal taxAmount = money(pct(discountedSubtotal, taxPercent));
        BigDecimal sourceTotal = money(discountedSubtotal.add(taxAmount));

        BigDecimal buyTotal = calculateBuyCost(tenantId, r, mode, weight, sourceCurrency, charges, asOf);
        BigDecimal convertedTotal = convert(tenantId, sourceTotal, sourceCurrency, requestedCurrency, asOf);
        BigDecimal convertedBuy = convert(tenantId, buyTotal, sourceCurrency, requestedCurrency, asOf);
        BigDecimal profit = money(convertedTotal.subtract(convertedBuy));
        BigDecimal margin = convertedTotal.signum() == 0
                ? BigDecimal.ZERO
                : profit.multiply(BigDecimal.valueOf(100)).divide(convertedTotal, 2, RoundingMode.HALF_UP);

        Map<String, Object> marginControl = findMarginControl(tenantId, r.clientId(), mode, asOf);
        BigDecimal minimumMargin = marginControl == null ? new BigDecimal("15") : dec(marginControl.get("minimum_margin_percent"));
        boolean marginWarning = margin.compareTo(minimumMargin) < 0;
        boolean hardBlocked = marginWarning && marginControl != null && Boolean.TRUE.equals(marginControl.get("hard_block"));
        if (hardBlocked) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Quoted margin " + margin + "% is below the configured minimum of " + minimumMargin + "%");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("sourceCurrency", sourceCurrency);
        result.put("currency", requestedCurrency);
        result.put("weightKg", weight);
        result.put("rawWeightCharge", raw);
        result.put("baseCharge", base);
        result.put("markupAmount", money(markedBase.subtract(base)));
        result.put("markupPercent", markupPercent);
        result.put("fuelSurcharge", fuelAmount);
        result.put("securitySurcharge", securityAmount);
        result.put("accessorialTotal", money(accessorialTotal));
        result.put("discountPercent", discountPercent);
        result.put("discountAmount", discountAmount);
        result.put("taxAmount", taxAmount);
        result.put("totalCharge", convertedTotal);
        result.put("buyTotal", convertedBuy);
        result.put("expectedProfit", profit);
        result.put("marginPercent", margin);
        result.put("minimumMarginPercent", minimumMargin);
        result.put("marginWarning", marginWarning);
        result.put("pricingSource", pricingSource);
        result.put("charges", charges);
        result.put("fxRate", fxRate(tenantId, sourceCurrency, requestedCurrency, asOf));
        return result;
    }

    private BigDecimal calculateBuyCost(UUID tenantId, RatePreviewRequest r, String mode, BigDecimal weight,
                                        String sellCurrency, List<Map<String, Object>> extraCharges, LocalDate asOf) {
        Map<String, Object> buyRate = findCarrierBuyRate(tenantId, r.carrierId(), r.laneCode(), mode);
        BigDecimal buy = BigDecimal.ZERO;
        if (buyRate != null) {
            String buyCurrency = currency(Objects.toString(buyRate.get("currency"), sellCurrency));
            BigDecimal rate = dec(buyRate.get("base_rate_per_kg"));
            BigDecimal min = dec(buyRate.get("min_charge"));
            BigDecimal base = rate.multiply(weight).max(min);
            BigDecimal providerTotal = base
                    .add(pct(base, dec(buyRate.get("fuel_percent"))))
                    .add(pct(base, dec(buyRate.get("security_percent"))));
            buy = buy.add(convert(tenantId, providerTotal, buyCurrency, sellCurrency, asOf));
        }
        for (Map<String, Object> charge : extraCharges) {
            String chargeCurrency = currency(Objects.toString(charge.get("currency"), sellCurrency));
            buy = buy.add(convert(tenantId, dec(charge.get("buyAmount")), chargeCurrency, sellCurrency, asOf));
        }
        return money(buy);
    }

    private Map<String, Object> findCustomerRateCard(UUID tenantId, UUID clientId, String lane, String mode, LocalDate asOf) {
        if (clientId == null) return null;
        return oneOrNull("SELECT * FROM customer_rate_cards WHERE tenant_id=? AND client_id=? AND upper(mode)=? AND active=true "
                + "AND (lane_code IS NULL OR upper(lane_code)=upper(?)) AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) "
                + "ORDER BY (lane_code IS NOT NULL) DESC,valid_from DESC LIMIT 1",
                tenantId, clientId, mode, nullable(lane), asOf, asOf);
    }

    private Map<String, Object> findPricingRule(UUID tenantId, UUID clientId, UUID carrierId, String lane, String mode,
                                                  BigDecimal weight, LocalDate asOf) {
        return oneOrNull("SELECT * FROM pricing_rules WHERE tenant_id=? AND upper(mode)=? AND active=true "
                        + "AND (client_id IS NULL OR client_id=?) AND (carrier_id IS NULL OR carrier_id=?) "
                        + "AND (lane_code IS NULL OR upper(lane_code)=upper(?)) "
                        + "AND (min_weight_kg IS NULL OR min_weight_kg<=?) AND (max_weight_kg IS NULL OR max_weight_kg>=?) "
                        + "AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) "
                        + "ORDER BY (client_id IS NOT NULL) DESC,(carrier_id IS NOT NULL) DESC,(lane_code IS NOT NULL) DESC,priority ASC,valid_from DESC LIMIT 1",
                tenantId, mode, clientId, carrierId, nullable(lane), weight, weight, asOf, asOf);
    }

    private Map<String, Object> findRateCard(UUID tenantId, UUID clientId, UUID carrierId, String lane, String mode, LocalDate asOf) {
        return oneOrNull("SELECT * FROM rate_cards WHERE tenant_id=? AND upper(transport_mode)=? AND active=true "
                        + "AND (client_id IS NULL OR client_id=?) AND (carrier_id IS NULL OR carrier_id=?) "
                        + "AND (lane_code IS NULL OR upper(lane_code)=upper(?)) "
                        + "AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) "
                        + "ORDER BY (client_id IS NOT NULL) DESC,(carrier_id IS NOT NULL) DESC,(lane_code IS NOT NULL) DESC,valid_from DESC LIMIT 1",
                tenantId, mode, clientId, carrierId, nullable(lane), asOf, asOf);
    }

    private Map<String, Object> findCarrierBuyRate(UUID tenantId, UUID carrierId, String lane, String mode) {
        return oneOrNull("SELECT * FROM carrier_buy_rates WHERE tenant_id=? AND upper(mode)=? AND active=true "
                        + "AND (carrier_id IS NULL OR carrier_id=?) AND (lane_code IS NULL OR upper(lane_code)=upper(?)) "
                        + "AND valid_from<=CURRENT_DATE AND (valid_until IS NULL OR valid_until>=CURRENT_DATE) "
                        + "ORDER BY (carrier_id IS NOT NULL) DESC,(lane_code IS NOT NULL) DESC,valid_from DESC LIMIT 1",
                tenantId, mode, carrierId, nullable(lane));
    }

    private Map<String, Object> findDiscount(UUID tenantId, UUID clientId, String lane, String mode, LocalDate asOf) {
        return oneOrNull("SELECT * FROM pricing_discounts WHERE tenant_id=? AND active=true "
                        + "AND (client_id IS NULL OR client_id=?) AND (mode IS NULL OR upper(mode)=?) "
                        + "AND (lane_code IS NULL OR upper(lane_code)=upper(?)) AND valid_from<=? "
                        + "AND (valid_until IS NULL OR valid_until>=?) ORDER BY (client_id IS NOT NULL) DESC,(lane_code IS NOT NULL) DESC,priority ASC,valid_from DESC LIMIT 1",
                tenantId, clientId, mode, nullable(lane), asOf, asOf);
    }

    private Map<String, Object> findMarginControl(UUID tenantId, UUID clientId, String mode, LocalDate asOf) {
        return oneOrNull("SELECT * FROM pricing_margin_controls WHERE tenant_id=? AND active=true "
                        + "AND (mode IS NULL OR upper(mode)=?) AND (client_id IS NULL OR client_id=?) "
                        + "AND valid_from<=? AND (valid_until IS NULL OR valid_until>=?) "
                        + "ORDER BY (client_id IS NOT NULL) DESC,(mode IS NOT NULL) DESC,valid_from DESC LIMIT 1",
                tenantId, mode, clientId, asOf, asOf);
    }

    private BigDecimal convert(UUID tenantId, BigDecimal amount, String from, String to, LocalDate asOf) {
        if (from.equalsIgnoreCase(to)) return money(amount);
        BigDecimal fx = fxRate(tenantId, from, to, asOf);
        return money(amount.multiply(fx));
    }

    private BigDecimal fxRate(UUID tenantId, String from, String to, LocalDate asOf) {
        if (from.equalsIgnoreCase(to)) return BigDecimal.ONE;
        try {
            BigDecimal direct = tenantDb.queryForObject(
                    "SELECT rate FROM finance_fx_rates WHERE tenant_id=? AND base_currency=? AND quote_currency=? AND rate_date<=? ORDER BY rate_date DESC,effective_at DESC NULLS LAST LIMIT 1",
                    BigDecimal.class, tenantId, from, to, asOf);
            return direct == null ? BigDecimal.ONE : direct;
        } catch (EmptyResultDataAccessException ignored) {
            try {
                BigDecimal inverse = tenantDb.queryForObject(
                        "SELECT rate FROM finance_fx_rates WHERE tenant_id=? AND base_currency=? AND quote_currency=? AND rate_date<=? ORDER BY rate_date DESC,effective_at DESC NULLS LAST LIMIT 1",
                        BigDecimal.class, tenantId, to, from, asOf);
                return inverse == null || inverse.signum() == 0
                        ? BigDecimal.ONE
                        : BigDecimal.ONE.divide(inverse, 10, RoundingMode.HALF_UP);
            } catch (EmptyResultDataAccessException noFx) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No FX rate configured for " + from + "/" + to + " on " + asOf);
            }
        }
    }

    private Map<String, Object> oneOrNull(String sql, Object... args) {
        try {
            return tenantDb.queryForMap(sql, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(nz(percent)).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal dec(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static BigDecimal dec(Object first, BigDecimal fallback) {
        return first == null ? fallback : dec(first);
    }

    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String currency(String value) {
        String v = value == null || value.isBlank() ? "USD" : value.trim().toUpperCase(Locale.ROOT);
        if (!v.matches("[A-Z]{3}")) throw bad("Currency must be an ISO-4217 three-letter code");
        return v;
    }

    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
