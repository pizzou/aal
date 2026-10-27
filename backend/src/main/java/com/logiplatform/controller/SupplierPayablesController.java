package com.logiplatform.controller;

import com.logiplatform.service.SupplierPayablesService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Finance-only supplier invoice, approval and settlement API. */
@RestController
@RequestMapping("/api/finance/supplier-bills")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
public class SupplierPayablesController {
    private final SupplierPayablesService service;
    public SupplierPayablesController(SupplierPayablesService service) { this.service = service; }

    @PostMapping
    public SupplierPayablesService.Bill create(@Valid @RequestBody SupplierPayablesService.BillRequest bill) {
        return service.create(bill);
    }
    @GetMapping
    public List<SupplierPayablesService.Bill> list(@RequestParam(required = false) UUID shipmentId) {
        return service.list(shipmentId);
    }
    @PostMapping("/{id}/approve")
    public SupplierPayablesService.Bill approve(@PathVariable UUID id) { return service.approve(id); }
    @PostMapping("/{id}/payments")
    public SupplierPayablesService.Payment pay(@PathVariable UUID id,
            @Valid @RequestBody SupplierPayablesService.PaymentRequest request) {
        return service.settle(id, request);
    }
    @GetMapping("/{id}/payments")
    public List<SupplierPayablesService.Payment> paymentHistory(@PathVariable UUID id) {
        return service.payments(id);
    }
}
