package com.logiplatform.controller;

import com.logiplatform.service.FinanceProfitAllocationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/profit-allocation")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
public class FinanceProfitAllocationController {
    private final FinanceProfitAllocationService service;

    public FinanceProfitAllocationController(FinanceProfitAllocationService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    public List<FinanceProfitAllocationService.RuleView> rules() {
        return service.rules();
    }

    @PutMapping("/rules")
    public List<FinanceProfitAllocationService.RuleView> saveRules(
            @Valid @RequestBody RulesRequest request) {
        return service.saveRules(request.rules().stream()
                .map(rule -> new FinanceProfitAllocationService.RuleInput(
                        rule.bankDestinationId(), rule.percentage()))
                .toList());
    }

    @PostMapping("/shipments/{shipmentId}/allocate")
    public FinanceProfitAllocationService.AllocationResult allocateShipment(
            @PathVariable UUID shipmentId) {
        return service.allocateShipment(shipmentId);
    }

    @GetMapping("/runs")
    public List<FinanceProfitAllocationService.RunSummary> recentRuns() {
        return service.recentRuns();
    }

    public record RulesRequest(@NotNull List<@Valid RuleInput> rules) {}
    public record RuleInput(
            @NotNull UUID bankDestinationId,
            @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal percentage) {}
}
