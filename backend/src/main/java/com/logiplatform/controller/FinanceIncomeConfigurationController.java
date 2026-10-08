package com.logiplatform.controller;

import com.logiplatform.model.CommercialPaymentAllocation;
import com.logiplatform.model.FinanceBankDestination;
import com.logiplatform.model.FinanceIncomeAllocationRule;
import com.logiplatform.model.FinanceIncomeSource;
import com.logiplatform.service.FinanceIncomeAllocationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/income-configuration")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
public class FinanceIncomeConfigurationController {
    private final FinanceIncomeAllocationService service;

    public FinanceIncomeConfigurationController(FinanceIncomeAllocationService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<FinanceIncomeSource> sources() { return service.sources(); }

    @PostMapping("/sources")
    public FinanceIncomeSource createSource(@Valid @RequestBody SourceRequest r) {
        return service.createSource(r.code(), r.name(), r.description());
    }

    @PutMapping("/sources/{id}")
    public FinanceIncomeSource updateSource(@PathVariable UUID id,
                                            @Valid @RequestBody SourceUpdateRequest r) {
        return service.updateSource(id, r.code(), r.name(), r.description(), r.active());
    }

    @GetMapping("/banks")
    public List<FinanceBankDestination> banks() { return service.banks(); }

    @PostMapping("/banks")
    public FinanceBankDestination createBank(@Valid @RequestBody BankRequest r) {
        return service.createBank(r.code(), r.name(), r.accountReference(), r.description());
    }

    @PutMapping("/banks/{id}")
    public FinanceBankDestination updateBank(@PathVariable UUID id,
                                             @Valid @RequestBody BankUpdateRequest r) {
        return service.updateBank(id, r.code(), r.name(), r.accountReference(),
                r.description(), r.active());
    }

    @GetMapping("/rules")
    public List<FinanceIncomeAllocationRule> rules() { return service.rules(); }

    @PostMapping("/rules")
    public FinanceIncomeAllocationRule createRule(@Valid @RequestBody RuleRequest r) {
        return service.createRule(r.incomeSourceId(), r.bankDestinationId(), r.percentage());
    }

    @PutMapping("/rules/{id}")
    public FinanceIncomeAllocationRule updateRule(@PathVariable UUID id,
                                                  @Valid @RequestBody RuleUpdateRequest r) {
        return service.updateRule(id, r.percentage(), r.active());
    }

    @GetMapping("/payments/{paymentId}/allocations")
    public List<CommercialPaymentAllocation> paymentAllocations(
            @PathVariable UUID paymentId) {
        return service.paymentAllocations(paymentId);
    }

    public record SourceRequest(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String description) {}
    public record SourceUpdateRequest(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String description,
            boolean active) {}
    public record BankRequest(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 160) String accountReference,
            @Size(max = 500) String description) {}
    public record BankUpdateRequest(
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 160) String accountReference,
            @Size(max = 500) String description,
            boolean active) {}
    public record RuleRequest(
            @NotNull UUID incomeSourceId,
            @NotNull UUID bankDestinationId,
            @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal percentage) {}
    public record RuleUpdateRequest(
            @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal percentage,
            boolean active) {}
}
