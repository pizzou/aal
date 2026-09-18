package com.logiplatform.controller;

import com.logiplatform.service.BillingService;
import com.logiplatform.model.CommercialInvoice;

import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService service;

    public BillingController(BillingService s) {
        service = s;
    }

    public record BillRequest(@NotNull LocalDate dueDate, String owner) {
    }

    public record PaymentRequest(@NotNull @Positive BigDecimal amount, String reference) {
    }

    @PostMapping("/shipments/{shipmentId}/invoice")
    public CommercialInvoice invoice(@PathVariable UUID shipmentId,
            @jakarta.validation.Valid @RequestBody BillRequest r) {
        return service.billShipment(shipmentId, r.dueDate(), r.owner());
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    public CommercialInvoice payment(@PathVariable UUID invoiceId,
            @RequestHeader(value = "Idempotency-Key", required = true) String key,
            @jakarta.validation.Valid @RequestBody PaymentRequest r) {
        return service.recordPayment(invoiceId, r.amount(), key, r.reference());
    }
}
