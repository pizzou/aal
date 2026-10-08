package com.logiplatform.controller;

import com.logiplatform.service.CommercialQuoteDocumentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/quotes")
public class CommercialQuoteDocumentController {
    private final CommercialQuoteDocumentService documents;
    public CommercialQuoteDocumentController(CommercialQuoteDocumentService documents){this.documents=documents;}

    @GetMapping(value="/{quoteId}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID quoteId){
        byte[] body=documents.quotePdf(quoteId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("quotation-"+quoteId+".pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);
    }

    @PostMapping("/{quoteId}/secure-link")
    public Map<String,Object> link(@PathVariable UUID quoteId,@RequestParam(defaultValue="24") long hours){
        return Map.of("url",documents.secureLink(quoteId,hours),"expiresInHours",hours);
    }
}
