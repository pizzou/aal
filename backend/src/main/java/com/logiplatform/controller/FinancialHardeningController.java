package com.logiplatform.controller;

import com.logiplatform.service.FinancialDocumentArchiveService;
import com.logiplatform.service.FinancialHardeningService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/hardening")
public class FinancialHardeningController {
    private final FinancialHardeningService service;
    private final FinancialDocumentArchiveService archive;

    public FinancialHardeningController(FinancialHardeningService service, FinancialDocumentArchiveService archive){
        this.service=service;this.archive=archive;
    }

    @PostMapping("/invoices/{invoiceId}/lifecycle")
    public Map<String,Object> lifecycle(@PathVariable UUID invoiceId,@Valid @RequestBody LifecycleRequest r){
        return service.transitionInvoice(invoiceId,r.status(),r.reason());
    }

    @GetMapping("/invoices/{invoiceId}/lifecycle-history")
    public java.util.List<Map<String,Object>> lifecycleHistory(@PathVariable UUID invoiceId){
        return service.lifecycleHistory(invoiceId);
    }

    @PostMapping("/invoices/{invoiceId}/tax")
    public Map<String,Object> applyTaxToDraftInvoice(@PathVariable UUID invoiceId,@Valid @RequestBody ApplyInvoiceTaxRequest r){
        return service.applyTaxToDraftInvoice(invoiceId,r.jurisdictionCode(),r.taxCode(),r.onDate());
    }

    @GetMapping("/finance-notes")
    public java.util.List<Map<String,Object>> financeNotes(@RequestParam(required=false) String status,
                                                            @RequestParam(required=false) UUID invoiceId){
        return service.financeNotes(status,invoiceId);
    }

    @GetMapping("/tax-jurisdictions")
    public java.util.List<Map<String,Object>> taxJurisdictions(){ return service.taxJurisdictions(); }

    @GetMapping("/customer-statement")
    public Map<String,Object> statement(@RequestParam String client,
                                         @RequestParam(required=false) LocalDate from,
                                         @RequestParam(required=false) LocalDate to){
        return service.statement(client,from,to);
    }

    @GetMapping(value="/customer-statement/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statementPdf(@RequestParam String client,
                                               @RequestParam(required=false) LocalDate from,
                                               @RequestParam(required=false) LocalDate to){
        byte[] body=service.statementPdf(client,from,to);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("customer-statement.pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);
    }

    @PostMapping("/customer-statement/email")
    public ResponseEntity<Void> emailStatement(@Valid @RequestBody StatementEmailRequest r){
        service.emailStatement(r.client(),r.from(),r.to(),r.recipientEmail());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/finance-notes")
    public Map<String,Object> note(@Valid @RequestBody FinanceNoteRequest r){
        return service.createFinanceNote(r.noteType(),r.invoiceId(),r.shipmentId(),r.amount(),r.currency(),r.reason());
    }

    @PostMapping("/finance-notes/{id}/approve")
    public Map<String,Object> approveNote(@PathVariable UUID id){return service.approveFinanceNote(id);}

    @PostMapping("/finance-notes/{id}/void")
    public Map<String,Object> voidNote(@PathVariable UUID id,@Valid @RequestBody VoidNoteRequest r){return service.voidFinanceNote(id,r.reason());}

    @PostMapping("/tax-jurisdictions")
    public Map<String,Object> taxJurisdiction(@Valid @RequestBody TaxJurisdictionRequest r){
        return service.taxJurisdiction(r.code(),r.legalName(),r.registrationNo(),r.countryCode(),r.address(),r.invoicePrefix(),r.currency(),r.active(),r.defaultForInvoicing());
    }

    @PostMapping("/tax-rules")
    public Map<String,Object> upsertTaxRule(@Valid @RequestBody TaxRuleRequest r){
        return service.upsertTaxRule(r.jurisdictionCode(),r.code(),r.taxName(),r.rate(),r.withholdingRate(),r.currency(),
                r.validFrom(),r.validUntil(),r.taxType(),r.inclusive(),r.exemptionCode(),r.appliesTo(),r.active());
    }

    @GetMapping("/tax-rules")
    public java.util.List<Map<String,Object>> taxRules(@RequestParam String jurisdictionCode,
                                                        @RequestParam(required=false) LocalDate onDate){
        return service.taxRules(jurisdictionCode,onDate);
    }

    @PostMapping("/tax/calculate")
    public Map<String,Object> calculateTax(@Valid @RequestBody TaxCalculationRequest r){
        return service.calculateTax(r.jurisdictionCode(),r.taxCode(),r.amount(),r.currency(),r.onDate(),r.documentType(),r.documentId());
    }

    @PostMapping("/archives/{archiveId}/links")
    public Map<String,Object> issueLink(@PathVariable UUID archiveId,
                                        @RequestParam(defaultValue="24") long hours){
        String url=archive.issueLink(archiveId,Duration.ofHours(hours));
        return Map.of("archiveId",archiveId,"url",url,"expiresInHours",hours);
    }

    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<Void> revokeLink(@PathVariable UUID linkId){
        archive.revokeLink(linkId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/public/{token}")
    public ResponseEntity<byte[]> publicDownload(@PathVariable String token){
        FinancialDocumentArchiveService.StoredDocument d=archive.downloadByToken(token);
        if(d==null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"private,no-store").body(d.content());
    }

    public record LifecycleRequest(@NotBlank String status,String reason){}
    public record StatementEmailRequest(@NotBlank String client,LocalDate from,LocalDate to,@Email String recipientEmail){}
    public record FinanceNoteRequest(@NotBlank String noteType,UUID invoiceId,UUID shipmentId,
                                     @NotNull java.math.BigDecimal amount,@NotBlank String currency,@NotBlank String reason){}
    public record VoidNoteRequest(@NotBlank String reason){}
    public record TaxCalculationRequest(@NotBlank String jurisdictionCode,@NotBlank String taxCode,
                                        @NotNull @jakarta.validation.constraints.PositiveOrZero java.math.BigDecimal amount,
                                        @NotBlank String currency,LocalDate onDate,String documentType,UUID documentId){}
    public record TaxJurisdictionRequest(@NotBlank String code,@NotBlank String legalName,
                                         String registrationNo,@NotBlank String countryCode,String address,
                                         String invoicePrefix,@NotBlank String currency,boolean active,boolean defaultForInvoicing){}

    public record TaxRuleRequest(@NotBlank String jurisdictionCode,@NotBlank String code,@NotBlank String taxName,
                                 @NotNull @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("100.0") java.math.BigDecimal rate,
                                 @NotNull @jakarta.validation.constraints.DecimalMin("0.0") @jakarta.validation.constraints.DecimalMax("100.0") java.math.BigDecimal withholdingRate,
                                 String currency,@NotNull LocalDate validFrom,LocalDate validUntil,String taxType,
                                 boolean inclusive,String exemptionCode,String appliesTo,boolean active){}
    public record ApplyInvoiceTaxRequest(@NotBlank String jurisdictionCode,@NotBlank String taxCode,LocalDate onDate){}
}
