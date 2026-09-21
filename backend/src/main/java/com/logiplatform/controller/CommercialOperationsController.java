package com.logiplatform.controller;

import com.logiplatform.dto.CommercialDtos.*;
import com.logiplatform.service.CommercialOperationsService;
import com.logiplatform.service.PublicQuoteShareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/commercial")
public class CommercialOperationsController {
    private final CommercialOperationsService s;
    private final PublicQuoteShareService quoteSharing;
    private final com.logiplatform.service.QuoteLifecycleService quoteLifecycle;

    public CommercialOperationsController(CommercialOperationsService s, PublicQuoteShareService quoteSharing, com.logiplatform.service.QuoteLifecycleService quoteLifecycle) {
        this.s = s;
        this.quoteSharing = quoteSharing;
        this.quoteLifecycle = quoteLifecycle;
    }

    @PostMapping("/quotes")
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest r) {
        return s.createQuote(r);
    }

    @GetMapping("/quotes/{id}/versions")
    public List<Map<String,Object>> quoteVersions(@PathVariable UUID id) { return quoteLifecycle.versions(id); }

    @PostMapping("/quotes/{id}/versions")
    public Map<String,Object> createQuoteVersion(@PathVariable UUID id) { return quoteLifecycle.createVersion(id); }

    @PostMapping("/quotes/{id}/versions/revise")
    public Map<String,Object> reviseQuote(@PathVariable UUID id, @RequestBody Map<String,Object> changes) { return quoteLifecycle.revise(id, changes); }

    @PostMapping("/quotes/{id}/versions/{versionId}/lock")
    public Map<String,Object> lockQuote(@PathVariable UUID id, @PathVariable UUID versionId) { return quoteLifecycle.lock(id, versionId); }

    @PostMapping("/quotes/{id}/versions/{versionId}/approve")
    public Map<String,Object> approveQuote(@PathVariable UUID id, @PathVariable UUID versionId) { return quoteLifecycle.approve(id, versionId); }

    @PostMapping("/quotes/{id}/versions/{versionId}/accept")
    public Map<String,Object> acceptQuote(@PathVariable UUID id, @PathVariable UUID versionId) { return quoteLifecycle.accept(id, versionId); }

    @PostMapping("/quotes/{id}/share")
    public PublicQuoteShareService.QuoteShareResult shareQuote(@PathVariable UUID id, @RequestParam(required = false) String recipientEmail) {
        return quoteSharing.share(id, recipientEmail);
    }

    @PostMapping("/quotes/{id}/invoice")
    public InvoiceResponse invoiceFromQuote(@PathVariable UUID id, @RequestParam(required = false) String invoiceNo,
            @RequestParam(required = false) LocalDate dueDate) {
        return s.createInvoiceFromQuote(id, invoiceNo, dueDate);
    }

    @PostMapping("/quotes/{id}/convert")
    public QuoteToShipmentResponse convertQuote(@PathVariable UUID id,
            @RequestParam(required = false) String shipmentReference, @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination) {
        return s.convertQuoteToShipment(id, shipmentReference, origin, destination);
    }

    @GetMapping("/quotes")
    public List<QuoteResponse> quotes() {
        return s.listQuotes();
    }

    @PatchMapping("/quotes/{id}/status")
    public QuoteResponse quoteStatus(@PathVariable UUID id, @RequestParam String status) {
        return s.quoteStatus(id, status);
    }

    @PostMapping("/invoices")
    public InvoiceResponse invoice(@Valid @RequestBody InvoiceRequest r) {
        return s.createInvoice(r);
    }

    @GetMapping("/invoices")
    public List<InvoiceResponse> invoices() {
        return s.listInvoices();
    }

    @PostMapping("/invoices/{id}/payments")
    public InvoiceResponse payment(@PathVariable UUID id, @Valid @RequestBody PaymentRequest r) {
        return s.pay(id, r);
    }

    @PostMapping("/clients")
    public ClientResponse client(@Valid @RequestBody ClientRequest r) {
        return s.createClient(r);
    }

    @GetMapping("/clients")
    public List<ClientResponse> clients() {
        return s.clients();
    }

    @PatchMapping("/clients/{id}/status")
    public ClientResponse clientStatus(@PathVariable UUID id, @RequestParam String status) {
        return s.clientStatus(id, status);
    }

    @PostMapping("/partners")
    public PartnerResponse partner(@Valid @RequestBody PartnerRequest r) {
        return s.createPartner(r);
    }

    @GetMapping("/partners")
    public List<PartnerResponse> partners() {
        return s.partners();
    }

    @PostMapping("/tasks")
    public TaskResponse task(@Valid @RequestBody TaskRequest r) {
        return s.createTask(r);
    }

    @GetMapping("/tasks")
    public List<TaskResponse> tasks() {
        return s.tasks();
    }

    @PatchMapping("/tasks/{id}/status")
    public TaskResponse taskStatus(@PathVariable UUID id, @RequestParam String status) {
        return s.taskStatus(id, status);
    }

    @PostMapping("/expenses")
    public ExpenseResponse expense(@Valid @RequestBody ExpenseRequest r) {
        return s.createExpense(r);
    }

    @GetMapping("/expenses")
    public List<ExpenseResponse> expenses() {
        return s.expenses();
    }
}
