package com.logiplatform.controller;

import com.logiplatform.service.FinancialDocumentService;
import jakarta.validation.constraints.Email;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.time.Duration;

@RestController
@RequestMapping("/api/finance/documents")
public class FinancialDocumentController {
    private final FinancialDocumentService documents;

    public FinancialDocumentController(FinancialDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping(value="/invoices/{invoiceId}/pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID invoiceId) {
        return file(documents.invoicePdf(invoiceId), "invoice-" + invoiceId + ".pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/invoices/{invoiceId}/latest-receipt")
    public FinancialDocumentService.ReceiptReference latestReceipt(@PathVariable UUID invoiceId) {
        return documents.latestReceipt(invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/secure-link")
    public java.util.Map<String,Object> invoiceLink(@PathVariable UUID invoiceId,
                                                     @RequestParam(defaultValue="24") long hours) {
        return java.util.Map.of("url",documents.invoiceSecureLink(invoiceId,Duration.ofHours(hours)),
                "expiresInHours",hours);
    }

    @PostMapping("/invoices/{invoiceId}/email")
    public ResponseEntity<Void> emailInvoice(@PathVariable UUID invoiceId,
                                             @RequestBody(required=false) EmailDocumentRequest request) {
        documents.emailInvoice(invoiceId, request == null ? null : request.recipientEmail(),
                request == null ? null : request.recipientName());
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value="/payments/{paymentId}/receipt/pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptPdf(@PathVariable UUID paymentId) {
        return file(documents.receiptPdf(paymentId), "receipt-" + paymentId + ".pdf", MediaType.APPLICATION_PDF);
    }

    @PostMapping("/payments/{paymentId}/receipt/secure-link")
    public java.util.Map<String,Object> receiptLink(@PathVariable UUID paymentId,
                                                     @RequestParam(defaultValue="24") long hours) {
        return java.util.Map.of("url",documents.receiptSecureLink(paymentId,Duration.ofHours(hours)),
                "expiresInHours",hours);
    }

    @PostMapping("/payments/{paymentId}/receipt/email")
    public ResponseEntity<Void> emailReceipt(@PathVariable UUID paymentId,
                                             @RequestBody(required=false) EmailDocumentRequest request) {
        documents.emailReceipt(paymentId, request == null ? null : request.recipientEmail(),
                request == null ? null : request.recipientName());
        return ResponseEntity.accepted().build();
    }

    private static ResponseEntity<byte[]> file(byte[] body, String filename, MediaType type) {
        return ResponseEntity.ok().contentType(type).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    public record EmailDocumentRequest(@Email String recipientEmail, String recipientName) {}
}
