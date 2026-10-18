package com.logiplatform.controller;

import com.logiplatform.dto.EnterpriseCompletionDtos.*;
import com.logiplatform.service.EnterpriseCompletionService;
import com.logiplatform.service.FinancialHardeningService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/enterprise-completion")
public class EnterpriseCompletionExtrasController {
    private final EnterpriseCompletionService service;
    private final FinancialHardeningService financialHardening;

    public EnterpriseCompletionExtrasController(EnterpriseCompletionService service,
            FinancialHardeningService financialHardening) {
        this.service = service;
        this.financialHardening = financialHardening;
    }

    @GetMapping("/checklist")
    public List<Map<String, Object>> checklist() {
        return service.checklist();
    }

    @PostMapping("/carriers/contracts")
    public Map<String, Object> carrierContract(@Valid @RequestBody CarrierContractRequest request) {
        return service.upsertCarrierContract(request);
    }

    @PostMapping("/carriers/settlements")
    public Map<String, Object> carrierSettlement(@Valid @RequestBody CarrierSettlementRequest request) {
        return service.createCarrierSettlement(request);
    }

    @PostMapping("/carriers/settlements/{id}/approve")
    public Map<String, Object> approveCarrierSettlement(@PathVariable UUID id) {
        return service.approveCarrierSettlement(id);
    }

    @PostMapping("/finance/notes")
    public Map<String, Object> financeNote(@Valid @RequestBody FinanceNoteRequest request) {
        return service.createFinanceNote(request);
    }

    /**
     * Compatibility route for older clients. Tax rule persistence is centralized in
     * FinancialHardeningService so legacy requests cannot create unscoped duplicate
     * tax rows that bypass jurisdiction/effective-date validation.
     */
    @PostMapping("/finance/tax-rules")
    public Map<String, Object> taxRule(@Valid @RequestBody TaxRuleRequest request) {
        String jurisdictionCode = request.jurisdictionCode();
        if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
            jurisdictionCode = financialHardening.taxJurisdictions().stream()
                    .filter(j -> Boolean.TRUE.equals(j.get("active")))
                    .filter(j -> Boolean.TRUE.equals(j.get("default_for_invoicing"))
                            || Boolean.TRUE.equals(j.get("defaultForInvoicing")))
                    .map(j -> java.util.Objects.toString(j.get("jurisdiction_code"),
                            java.util.Objects.toString(j.get("jurisdictionCode"), "")))
                    .filter(code -> !code.isBlank())
                    .findFirst().orElse(null);
        }
        if (jurisdictionCode == null || jurisdictionCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This compatibility endpoint no longer creates unscoped tax rules. Configure an active default tax jurisdiction in Billing > Financial hardening first.");
        }
        return financialHardening.upsertTaxRule(jurisdictionCode, request.code(), request.taxName(),
                request.rate(), request.withholdingRate(), request.currency(), request.validFrom(),
                request.validUntil(), request.taxType(), Boolean.TRUE.equals(request.inclusive()),
                request.exemptionCode(), request.appliesTo(), true);
    }

    @GetMapping("/finance/customer-statements")
    public List<Map<String, Object>> customerStatement(@RequestParam String client) {
        return service.customerStatement(client);
    }

    @GetMapping("/finance/supplier-statements")
    public List<Map<String, Object>> supplierStatement(@RequestParam String supplier) {
        return service.supplierStatement(supplier);
    }

    @PostMapping("/finance/periods/{id}/close")
    public Map<String, Object> closePeriod(@PathVariable UUID id) {
        return service.closeFinancePeriod(id);
    }

    @PostMapping("/finance/accounting-export")
    public Map<String, Object> accountingExport(@Valid @RequestBody AccountingExportRequest request) {
        return service.generateAccountingExport(request);
    }

    @PostMapping("/sla/evaluate")
    public Map<String, Object> evaluateSla(@RequestBody SlaEvaluationRequest request) {
        return service.evaluateSla(request);
    }

    @PostMapping("/exceptions/escalate")
    public Map<String, Object> escalate(@Valid @RequestBody EscalationRequest request) {
        return service.escalate(request);
    }

    @PostMapping("/ocean/shipping-instructions")
    public Map<String, Object> shippingInstructions(@Valid @RequestBody OceanShippingInstructionRequest request) {
        return service.oceanShippingInstructions(request);
    }

    @PostMapping("/ocean/bills")
    public Map<String, Object> oceanBill(@Valid @RequestBody OceanBillOfLadingRequest request) {
        return service.oceanBill(request);
    }

    @PostMapping("/documents/{id}/review")
    public Map<String, Object> reviewDocument(
            @PathVariable UUID id,
            @Valid @RequestBody DocumentReviewRequest request) {
        return service.reviewDocument(id, request);
    }

    @PostMapping("/customs/submit")
    public Map<String, Object> submitCustoms(@Valid @RequestBody CustomsSubmissionRequest request) {
        return service.submitCustoms(request);
    }

    @PostMapping("/customs/{id}/release")
    public Map<String, Object> releaseCustoms(@PathVariable UUID id) {
        return service.releaseCustoms(id);
    }

    @PostMapping("/routes")
    public Map<String, Object> route(@Valid @RequestBody RoutePlanRequest request) {
        return service.routePlan(request);
    }

    @PostMapping("/mobile/devices")
    public Map<String, Object> device(@Valid @RequestBody MobileDeviceRequest request) {
        return service.registerDevice(request);
    }

    @PostMapping("/mobile/push-subscriptions")
    public Map<String, Object> push(@Valid @RequestBody PushSubscriptionRequest request) {
        return service.pushSubscription(request);
    }

    @PostMapping("/mobile/apply-sync")
    public Map<String, Object> sync(@Valid @RequestBody MobileSyncApplyRequest request) {
        return service.applyMobileSync(request);
    }

    @GetMapping("/analytics/forecast")
    public Map<String, Object> forecast() {
        return service.analyticsForecast();
    }

    @GetMapping("/integrations/{code}/health")
    public Map<String, Object> integrationHealth(@PathVariable String code) {
        return service.integrationHealth(code);
    }

    @PostMapping("/automation/tick")
    public Map<String, Object> automationTick(@RequestBody AutomationTickRequest request) {
        return service.runAutomationTick(request);
    }
}
