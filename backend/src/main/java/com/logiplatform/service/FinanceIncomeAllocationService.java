package com.logiplatform.service;

import com.logiplatform.model.CommercialPayment;
import com.logiplatform.model.CommercialPaymentAllocation;
import com.logiplatform.model.FinanceBankDestination;
import com.logiplatform.model.FinanceIncomeAllocationRule;
import com.logiplatform.model.FinanceIncomeSource;
import com.logiplatform.repository.CommercialPaymentAllocationRepository;
import com.logiplatform.repository.FinanceBankDestinationRepository;
import com.logiplatform.repository.FinanceIncomeAllocationRuleRepository;
import com.logiplatform.repository.FinanceIncomeSourceRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class FinanceIncomeAllocationService {
    private static final BigDecimal HUNDRED = new BigDecimal("100.0000");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private final FinanceIncomeSourceRepository sources;
    private final FinanceBankDestinationRepository banks;
    private final FinanceIncomeAllocationRuleRepository rules;
    private final CommercialPaymentAllocationRepository allocations;

    public FinanceIncomeAllocationService(
            FinanceIncomeSourceRepository sources,
            FinanceBankDestinationRepository banks,
            FinanceIncomeAllocationRuleRepository rules,
            CommercialPaymentAllocationRepository allocations) {
        this.sources = sources;
        this.banks = banks;
        this.rules = rules;
        this.allocations = allocations;
    }

    @Transactional(readOnly = true)
    public List<FinanceIncomeSource> sources() {
        return sources.findAllByTenantIdOrderByName(TenantContext.getTenantId());
    }

    @Transactional(readOnly = true)
    public List<FinanceBankDestination> banks() {
        return banks.findAllByTenantIdOrderByName(TenantContext.getTenantId());
    }

    @Transactional(readOnly = true)
    public List<FinanceIncomeAllocationRule> rules() {
        return rules.findAllByTenantIdOrderByIncomeSourceIdBankDestinationId(
                TenantContext.getTenantId());
    }

    @Transactional
    public FinanceIncomeSource createSource(String code, String name, String description) {
        UUID tenant = TenantContext.getTenantId();
        String c = required(code, "Source code");
        String n = required(name, "Source name");
        if (sources.findByTenantIdAndCode(tenant, c).isPresent()) {
            throw conflict("Income source code already exists");
        }
        return sources.save(new FinanceIncomeSource(tenant, c, n, normalize(description)));
    }

    @Transactional
    public FinanceIncomeSource updateSource(UUID id, String code, String name,
                                            String description, boolean active) {
        UUID tenant = TenantContext.getTenantId();
        FinanceIncomeSource source = sources.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> notFound("Income source not found"));
        String c = required(code, "Source code");
        sources.findByTenantIdAndCode(tenant, c)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> { throw conflict("Income source code already exists"); });
        source.update(c, required(name, "Source name"), normalize(description), active);
        return source;
    }

    @Transactional
    public FinanceBankDestination createBank(String code, String name,
                                             String accountReference, String description) {
        UUID tenant = TenantContext.getTenantId();
        String c = required(code, "Bank code");
        if (banks.findByTenantIdAndCode(tenant, c).isPresent()) {
            throw conflict("Bank destination code already exists");
        }
        return banks.save(new FinanceBankDestination(
                tenant, c, required(name, "Bank name"),
                normalize(accountReference), normalize(description)));
    }

    @Transactional
    public FinanceBankDestination updateBank(UUID id, String code, String name,
                                             String accountReference, String description,
                                             boolean active) {
        UUID tenant = TenantContext.getTenantId();
        FinanceBankDestination bank = banks.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> notFound("Bank destination not found"));
        String c = required(code, "Bank code");
        banks.findByTenantIdAndCode(tenant, c)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> { throw conflict("Bank destination code already exists"); });
        bank.update(c, required(name, "Bank name"), normalize(accountReference),
                normalize(description), active);
        return bank;
    }

    @Transactional
    public FinanceIncomeAllocationRule createRule(UUID sourceId, UUID bankId,
                                                   BigDecimal percentage) {
        UUID tenant = TenantContext.getTenantId();
        requireActiveSource(tenant, sourceId);
        requireActiveBank(tenant, bankId);
        BigDecimal p = percentage(percentage);
        if (rules.findAllByTenantIdAndIncomeSourceIdAndActiveTrue(tenant, sourceId)
                .stream().anyMatch(r -> r.getBankDestinationId().equals(bankId))) {
            throw conflict("An allocation rule already exists for this source and bank");
        }
        FinanceIncomeAllocationRule rule =
                rules.saveAndFlush(new FinanceIncomeAllocationRule(tenant, sourceId, bankId, p));
        ensureRuleTotalNotOver100(tenant, sourceId);
        return rule;
    }

    @Transactional
    public FinanceIncomeAllocationRule updateRule(UUID id, BigDecimal percentage,
                                                   boolean active) {
        UUID tenant = TenantContext.getTenantId();
        FinanceIncomeAllocationRule rule = rules.findByTenantIdAndId(tenant, id)
                .orElseThrow(() -> notFound("Allocation rule not found"));
        BigDecimal p = percentage(percentage);
        rule.update(p, active);
        if (active) ensureRuleTotalNotOver100(tenant, rule.getIncomeSourceId());
        return rule;
    }

    @Transactional
    public void applyRules(CommercialPayment payment) {
        if (payment.getIncomeSourceId() == null) return;

        UUID tenant = payment.getTenantId();
        requireActiveSource(tenant, payment.getIncomeSourceId());

        List<FinanceIncomeAllocationRule> activeRules =
                rules.findAllByTenantIdAndIncomeSourceIdAndActiveTrue(
                        tenant, payment.getIncomeSourceId());

        if (activeRules.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "No income allocation rules are configured for the selected income source");
        }

        BigDecimal total = activeRules.stream()
                .map(FinanceIncomeAllocationRule::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.subtract(HUNDRED).abs().compareTo(TOLERANCE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Active income allocation rules must total exactly 100%");
        }

        for (FinanceIncomeAllocationRule rule : activeRules) {
            allocations.save(new CommercialPaymentAllocation(
                    tenant,
                    payment.getId(),
                    rule.getBankDestinationId(),
                    rule.getPercentage()));
        }
    }

    @Transactional(readOnly = true)
    public List<CommercialPaymentAllocation> paymentAllocations(UUID paymentId) {
        return allocations.findAllByTenantIdAndPaymentIdOrderByPercentageDesc(
                TenantContext.getTenantId(), paymentId);
    }

    private void ensureRuleTotalNotOver100(UUID tenant, UUID sourceId) {
        BigDecimal total = rules.findAllByTenantIdAndIncomeSourceIdAndActiveTrue(tenant, sourceId)
                .stream()
                .map(FinanceIncomeAllocationRule::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Active income allocation rules cannot exceed 100%");
        }
    }

    private void requireActiveSource(UUID tenant, UUID id) {
        if (id == null || sources.findByTenantIdAndId(tenant, id)
                .filter(FinanceIncomeSource::isActive).isEmpty()) {
            throw notFound("Active income source not found");
        }
    }

    private void requireActiveBank(UUID tenant, UUID id) {
        if (id == null || banks.findByTenantIdAndId(tenant, id)
                .filter(FinanceBankDestination::isActive).isEmpty()) {
            throw notFound("Active bank destination not found");
        }
    }

    private static BigDecimal percentage(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Allocation percentage must be between 0 and 100");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
