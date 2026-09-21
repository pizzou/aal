package com.logiplatform.controller;

import com.logiplatform.dto.AdvancedLogisticsDtos.*;
import com.logiplatform.service.AdvancedLogisticsService;
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

    @GetMapping("/capabilities")
    public Map<String,Object> capabilities() { return service.capabilities(); }

    @GetMapping("/shipments/{shipmentId}/360")
    public Map<String,Object> shipment360(@PathVariable UUID shipmentId) { return service.shipment360(shipmentId); }

    @PostMapping("/rating/preview")
    public Map<String,Object> rate(@Valid @RequestBody RatePreviewRequest request) { return service.advancedRate(request); }

    @PostMapping("/pricing-rules")
    public Map<String,Object> pricingRule(@Valid @RequestBody PricingRuleRequest request) { return service.pricingRule(request); }

    @GetMapping("/pricing-rules")
    public List<Map<String,Object>> pricingRules() { return service.pricingRules(); }

    @PostMapping("/customer-rate-cards")
    public Map<String,Object> customerRateCard(@Valid @RequestBody CustomerRateCardRequest request) { return service.customerRateCard(request); }

    @PostMapping("/carrier-buy-rates")
    public Map<String,Object> carrierBuyRate(@Valid @RequestBody CarrierBuyRateRequest request) { return service.carrierBuyRate(request); }


    @PostMapping("/quotes/{quoteId}/charges")
    public Map<String,Object> quoteCharge(@PathVariable UUID quoteId, @Valid @RequestBody QuoteChargeRequest request) {
        return service.addQuoteCharge(quoteId, request);
    }

    @PostMapping("/shipments/{shipmentId}/readiness")
    public Map<String,Object> readiness(@PathVariable UUID shipmentId, @RequestBody(required=false) ReadinessRequest request) {
        return service.readiness(shipmentId, request == null ? new ReadinessRequest("MANUAL") : request);
    }

    @PostMapping("/shipments/{shipmentId}/exceptions/evaluate")
    public Map<String,Object> exceptions(@PathVariable UUID shipmentId) { return service.evaluateExceptions(shipmentId); }

    @PostMapping("/ocean/free-time-rules")
    public Map<String,Object> oceanFreeTimeRule(@Valid @RequestBody OceanFreeTimeRuleRequest request) { return service.oceanFreeTimeRule(request); }

    @GetMapping("/ocean/free-time-rules")
    public List<Map<String,Object>> oceanFreeTimeRules() { return service.oceanFreeTimeRules(); }

    @PostMapping("/ocean/shipments/{shipmentId}/charges")
    public Map<String,Object> oceanCharge(@PathVariable UUID shipmentId, @Valid @RequestBody OceanChargeRequest request) {
        return service.oceanCharge(shipmentId, request);
    }

    @PostMapping("/warehouse/barcodes")
    public Map<String,Object> barcode(@Valid @RequestBody WarehouseBarcodeRequest request) { return service.barcode(request); }

    @PostMapping("/warehouse/cycle-counts")
    public Map<String,Object> cycleCount(@Valid @RequestBody CycleCountRequest request) { return service.cycleCount(request); }

    @PostMapping("/customs/lines")
    public Map<String,Object> customsLine(@Valid @RequestBody CustomsLineRequest request) { return service.customsLine(request); }

    @PostMapping("/finance/supplier-bills")
    public Map<String,Object> supplierBill(@Valid @RequestBody SupplierBillRequest request) { return service.supplierBill(request); }

    @PostMapping("/finance/bank-transactions")
    public Map<String,Object> bankTransaction(@Valid @RequestBody BankTransactionRequest request) { return service.bankTransaction(request); }

    @PostMapping("/finance/bank-transactions/{id}/reconcile")
    public Map<String,Object> reconcileBankTransaction(@PathVariable UUID id) { return service.reconcileBankTransaction(id); }

    @PostMapping("/carriers/performance")
    public Map<String,Object> carrierPerformance(@Valid @RequestBody CarrierPerformanceRequest request) { return service.carrierPerformance(request); }

    @PostMapping("/customer-feedback")
    public Map<String,Object> feedback(@Valid @RequestBody FeedbackRequest request) { return service.feedback(request); }

    @PostMapping("/webhooks")
    public Map<String,Object> webhook(@Valid @RequestBody WebhookRequest request) { return service.webhook(request); }

    @PostMapping("/mobile/sync")
    public Map<String,Object> mobileSync(@Valid @RequestBody MobileSyncRequest request) { return service.mobileSync(request); }

    @PostMapping("/workflow-rules")
    public Map<String,Object> workflowRule(@Valid @RequestBody WorkflowRuleRequest request) { return service.workflowRule(request); }

    @PostMapping("/documents/signatures")
    public Map<String,Object> signature(@Valid @RequestBody DocumentSignatureRequest request) { return service.signature(request); }

    @PostMapping("/automation/run")
    public Map<String,Object> automation(@Valid @RequestBody AutomationRequest request) { return service.runAutomation(request); }

    @GetMapping("/analytics")
    public Map<String,Object> analytics() { return service.analytics(); }

    @GetMapping("/integrations")
    public List<Map<String,Object>> integrations() { return service.integrations(); }
}
