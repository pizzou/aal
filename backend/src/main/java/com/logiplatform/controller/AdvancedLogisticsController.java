package com.logiplatform.controller;

import com.logiplatform.dto.AdvancedLogisticsDtos.*;
import com.logiplatform.service.AdvancedLogisticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/advanced-logistics")
public class AdvancedLogisticsController {
    private final AdvancedLogisticsService service;

    public AdvancedLogisticsController(AdvancedLogisticsService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','FINANCE')")
    @GetMapping("/capabilities")
    public Map<String,Object> capabilities() { return service.capabilities(); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','FINANCE','SALES','DISPATCH','WAREHOUSE','AIR_CARGO')")
    @GetMapping("/shipments/{shipmentId}/360")
    public Map<String,Object> shipment360(@PathVariable UUID shipmentId) { return service.shipment360(shipmentId); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','SALES')")
    @PostMapping("/rating/preview")
    public Map<String,Object> rate(@Valid @RequestBody RatePreviewRequest request) { return service.advancedRate(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @PostMapping("/pricing-rules")
    public Map<String,Object> pricingRule(@Valid @RequestBody PricingRuleRequest request) { return service.pricingRule(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @GetMapping("/pricing-rules")
    public List<Map<String,Object>> pricingRules() { return service.pricingRules(); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','SALES')")
    @PostMapping("/customer-rate-cards")
    public Map<String,Object> customerRateCard(@Valid @RequestBody CustomerRateCardRequest request) { return service.customerRateCard(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @PostMapping("/carrier-buy-rates")
    public Map<String,Object> carrierBuyRate(@Valid @RequestBody CarrierBuyRateRequest request) { return service.carrierBuyRate(request); }


    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','SALES')")
    @PostMapping("/quotes/{quoteId}/charges")
    public Map<String,Object> quoteCharge(@PathVariable UUID quoteId, @Valid @RequestBody QuoteChargeRequest request) {
        return service.addQuoteCharge(quoteId, request);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','DISPATCH')")
    @PostMapping("/shipments/{shipmentId}/readiness")
    public Map<String,Object> readiness(@PathVariable UUID shipmentId, @RequestBody(required=false) ReadinessRequest request) {
        return service.readiness(shipmentId, request == null ? new ReadinessRequest("MANUAL") : request);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','DISPATCH')")
    @PostMapping("/shipments/{shipmentId}/exceptions/evaluate")
    public Map<String,Object> exceptions(@PathVariable UUID shipmentId) { return service.evaluateExceptions(shipmentId); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','OPERATIONS')")
    @PostMapping("/ocean/free-time-rules")
    public Map<String,Object> oceanFreeTimeRule(@Valid @RequestBody OceanFreeTimeRuleRequest request) { return service.oceanFreeTimeRule(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','OPERATIONS')")
    @GetMapping("/ocean/free-time-rules")
    public List<Map<String,Object>> oceanFreeTimeRules() { return service.oceanFreeTimeRules(); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','OPERATIONS')")
    @PostMapping("/ocean/shipments/{shipmentId}/charges")
    public Map<String,Object> oceanCharge(@PathVariable UUID shipmentId, @Valid @RequestBody OceanChargeRequest request) {
        return service.oceanCharge(shipmentId, request);
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','WAREHOUSE')")
    @PostMapping("/warehouse/barcodes")
    public Map<String,Object> barcode(@Valid @RequestBody WarehouseBarcodeRequest request) { return service.barcode(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','WAREHOUSE')")
    @PostMapping("/warehouse/cycle-counts")
    public Map<String,Object> cycleCount(@Valid @RequestBody CycleCountRequest request) { return service.cycleCount(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','AIR_CARGO')")
    @PostMapping("/customs/lines")
    public Map<String,Object> customsLine(@Valid @RequestBody CustomsLineRequest request) { return service.customsLine(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @PostMapping("/finance/supplier-bills")
    public Map<String,Object> supplierBill(@Valid @RequestBody SupplierBillRequest request) { return service.supplierBill(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @PostMapping("/finance/bank-transactions")
    public Map<String,Object> bankTransaction(@Valid @RequestBody BankTransactionRequest request) { return service.bankTransaction(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    @PostMapping("/finance/bank-transactions/{id}/reconcile")
    public Map<String,Object> reconcileBankTransaction(@PathVariable UUID id) { return service.reconcileBankTransaction(id); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS')")
    @PostMapping("/carriers/performance")
    public Map<String,Object> carrierPerformance(@Valid @RequestBody CarrierPerformanceRequest request) { return service.carrierPerformance(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','SALES')")
    @PostMapping("/customer-feedback")
    public Map<String,Object> feedback(@Valid @RequestBody FeedbackRequest request) { return service.feedback(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/webhooks")
    public Map<String,Object> webhook(@Valid @RequestBody WebhookRequest request) { return service.webhook(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','DISPATCH','WAREHOUSE')")
    @PostMapping("/mobile/sync")
    public Map<String,Object> mobileSync(@Valid @RequestBody MobileSyncRequest request) { return service.mobileSync(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @PostMapping("/workflow-rules")
    public Map<String,Object> workflowRule(@Valid @RequestBody WorkflowRuleRequest request) { return service.workflowRule(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','FINANCE')")
    @PostMapping("/documents/signatures")
    public Map<String,Object> signature(@Valid @RequestBody DocumentSignatureRequest request) { return service.signature(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS')")
    @PostMapping("/automation/run")
    public Map<String,Object> automation(@Valid @RequestBody AutomationRequest request) { return service.runAutomation(request); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','FINANCE')")
    @GetMapping("/analytics")
    public Map<String,Object> analytics() { return service.analytics(); }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','FINANCE')")
    @GetMapping("/integrations")
    public List<Map<String,Object>> integrations() { return service.integrations(); }
}
