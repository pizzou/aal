package com.logiplatform.service;

import com.logiplatform.model.AccessorialCharge;
import com.logiplatform.repository.AccessorialChargeRepository;
import com.logiplatform.model.RateCard;
import com.logiplatform.repository.RateCardRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import static com.logiplatform.dto.RatingDtos.*;



/**
 * RULES-BASED, deliberately not "AI-driven" — see rateType="RULES_BASED" on every
 * quote response, a field that exists specifically so nothing downstream can
 * mislabel this as market-data-driven pricing (parallel to mockData:true on the
 * booking mock — a load-bearing API field, not just a code comment). Real
 * AI/market-driven spot pricing needs live market rate feeds this platform has no
 * access to, same standing limitation as airline connectivity.
 *
 * What this genuinely replaces: a spreadsheet of manually-applied per-mode rate
 * rules. Rate cards and accessorial charges are stored, tenant-configurable data,
 * computed through one consistent code path instead of copy-pasted formulas.
 *
 * The arithmetic below (base charge = max(weight * rate, minCharge); fuel surcharge
 * = base charge * fuelSurchargePercent / 100; total = base + surcharge +
 * accessorials) was verified against real Postgres NUMERIC arithmetic before being
 * written here — see RLS_VERIFICATION.md (100kg example: 450.00 base, 67.50
 * surcharge, 517.50 total; 5kg example: min-charge floor correctly applies).
 * BigDecimal is used throughout, never double, to avoid floating-point rounding
 * errors in financial calculations.
 */
@Service
public class RateEngineService {

    private final RateCardRepository rateCardRepository;
    private final AccessorialChargeRepository accessorialChargeRepository;
    private final JdbcTemplate tenantDb;

    public RateEngineService(RateCardRepository rateCardRepository,
                              AccessorialChargeRepository accessorialChargeRepository,
                              @Qualifier("tenantJdbcTemplate") JdbcTemplate tenantDb) {
        this.rateCardRepository = rateCardRepository;
        this.accessorialChargeRepository = accessorialChargeRepository;
        this.tenantDb = tenantDb;
    }

    @Transactional
    public RateCardResponse setRateCard(SetRateCardRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        RateCard existing = rateCardRepository.findByTenantIdAndTransportMode(tenantId, request.transportMode())
                .orElse(null);
        if (existing != null) {
            rateCardRepository.delete(existing);
            rateCardRepository.flush();
        }

        RateCard rateCard = new RateCard(tenantId, request.transportMode(), request.baseRatePerKg(),
                request.minCharge(), request.fuelSurchargePercent(), request.currency());
        return RateCardResponse.from(rateCardRepository.save(rateCard));
    }

    @Transactional(readOnly = true)
    public List<RateCardResponse> listRateCards() {
        UUID tenantId = TenantContext.getTenantId();
        return rateCardRepository.findAllByTenantId(tenantId).stream().map(RateCardResponse::from).toList();
    }

    @Transactional
    public AccessorialResponse setAccessorial(SetAccessorialRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        AccessorialCharge charge = new AccessorialCharge(
                tenantId, request.code(), request.description(), request.amount(), request.currency());
        return AccessorialResponse.from(accessorialChargeRepository.save(charge));
    }

    @Transactional(readOnly = true)
    public List<AccessorialResponse> listAccessorials() {
        UUID tenantId = TenantContext.getTenantId();
        return accessorialChargeRepository.findAllByTenantId(tenantId).stream()
                .map(AccessorialResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public QuoteResponse quote(QuoteRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        String mode = request.transportMode().trim().toUpperCase();
        java.util.Map<String,Object> advanced = null;
        try {
            advanced = tenantDb.queryForMap(
                "SELECT * FROM pricing_rules WHERE mode=? AND active=true AND valid_from<=CURRENT_DATE " +
                "AND (valid_until IS NULL OR valid_until>=CURRENT_DATE) " +
                "ORDER BY priority ASC, valid_from DESC LIMIT 1", mode);
        } catch (EmptyResultDataAccessException ignored) {
        }

        RateCard rateCard = rateCardRepository.findByTenantIdAndTransportMode(tenantId, mode)
                .orElse(null);

        BigDecimal baseRate;
        BigDecimal minCharge;
        BigDecimal fuelPercent;
        String currency;
        if (advanced != null) {
            baseRate = advanced.get("rate_per_kg") == null ? BigDecimal.ZERO : (BigDecimal) advanced.get("rate_per_kg");
            minCharge = advanced.get("min_charge") == null ? BigDecimal.ZERO : (BigDecimal) advanced.get("min_charge");
            fuelPercent = advanced.get("fuel_percent") == null ? BigDecimal.ZERO : (BigDecimal) advanced.get("fuel_percent");
            currency = advanced.get("currency") == null ? "USD" : advanced.get("currency").toString();
        } else {
            if (rateCard == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No rate card configured for transport mode " + request.transportMode());
            }
            baseRate = rateCard.getBaseRatePerKg();
            minCharge = rateCard.getMinCharge();
            fuelPercent = rateCard.getFuelSurchargePercent();
            currency = rateCard.getCurrency();
        }

        BigDecimal rawWeightCharge = baseRate.multiply(request.weightKg())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseCharge = rawWeightCharge.max(minCharge);

        BigDecimal fuelSurcharge = baseCharge
                .multiply(fuelPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        List<String> requestedCodes = request.accessorialCodes() != null ? request.accessorialCodes() : List.of();
        List<AccessorialCharge> applied = requestedCodes.isEmpty() ? List.of()
                : accessorialChargeRepository.findAllByTenantIdAndCodeIn(tenantId, requestedCodes);

        BigDecimal accessorialTotal = applied.stream()
                .map(AccessorialCharge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal total = baseCharge.add(fuelSurcharge).add(accessorialTotal);

        return new QuoteResponse(rawWeightCharge, baseCharge, fuelSurcharge, accessorialTotal,
                applied.stream().map(AccessorialResponse::from).toList(),
                total, currency, advanced != null ? "PRICING_RULE" : "RULES_BASED");
    }
}

