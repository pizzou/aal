package com.logiplatform.controller;

import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService service;
    public BillingController(BillingService service){this.service=service;}

    public record BillRequest(@NotNull LocalDate dueDate, String owner, String taxJurisdictionCode, String taxCode){}
    public record PaymentRequest(@NotNull @Positive BigDecimal amount,
                                 String currency, String reference, UUID incomeSourceId){}

    @PostMapping("/shipments/{shipmentId}/invoice/draft")
    public CommercialInvoice draftInvoice(@PathVariable UUID shipmentId,
                                          @Valid @RequestBody BillRequest request){
        return service.createDraftInvoice(shipmentId, request.dueDate(), request.owner(),
                request.taxJurisdictionCode(), request.taxCode());
    }

    @PostMapping("/shipments/{shipmentId}/invoice")
    public CommercialInvoice invoice(@PathVariable UUID shipmentId,
                                     @Valid @RequestBody BillRequest request){
        return service.billShipment(shipmentId, request.dueDate(), request.owner(),
                request.taxJurisdictionCode(), request.taxCode());
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    public CommercialInvoice payment(@PathVariable UUID invoiceId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody PaymentRequest request){
        return service.recordPayment(invoiceId,request.amount(),request.currency(),
                idempotencyKey,request.reference(),request.incomeSourceId());
    }
}
