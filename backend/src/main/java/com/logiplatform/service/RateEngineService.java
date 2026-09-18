package com.logiplatform.service;

import com.logiplatform.model.AccessorialCharge;
import com.logiplatform.repository.AccessorialChargeRepository;
import com.logiplatform.model.RateCard;
import com.logiplatform.repository.RateCardRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    public RateEngineService(RateCardRepository rateCardRepository,
                              AccessorialChargeRepository accessorialChargeRepository) {
        this.rateCardRepository = rateCardRepository;
        this.accessorialChargeRepository = accessorialChargeRepository;
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
        RateCard rateCard = rateCardRepository.findByTenantIdAndTransportMode(tenantId, request.transportMode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No rate card configured for transport mode " + request.transportMode()));

        BigDecimal rawWeightCharge = rateCard.getBaseRatePerKg().multiply(request.weightKg())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseCharge = rawWeightCharge.max(rateCard.getMinCharge());

        BigDecimal fuelSurcharge = baseCharge
                .multiply(rateCard.getFuelSurchargePercent())
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
                total, rateCard.getCurrency(), "RULES_BASED");
    }
}

