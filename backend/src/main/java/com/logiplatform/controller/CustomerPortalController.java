package com.logiplatform.controller;
import com.logiplatform.dto.CustomerPortalDtos.*;
import com.logiplatform.service.CustomerPortalService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
@RestController @RequestMapping("/api/customer")
public class CustomerPortalController {
 private final CustomerPortalService service; public CustomerPortalController(CustomerPortalService service){this.service=service;}
 @GetMapping("/profile") public Profile profile(){return service.profile();}
 @GetMapping("/shipments") public List<Shipment> shipments(){return service.shipments();}
 @GetMapping("/shipments/{id}") public Shipment shipment(@PathVariable UUID id){return service.shipment(id);}
 @GetMapping("/shipments/{id}/events") public List<Event> events(@PathVariable UUID id){return service.events(id);}
 @GetMapping("/shipments/{id}/documents") public List<Document> documents(@PathVariable UUID id){return service.documents(id);}
 @GetMapping("/quotes") public List<Quote> quotes(){return service.quotes();}
 @GetMapping("/invoices") public List<Invoice> invoices(){return service.invoices();}
 @GetMapping("/financial-documents") public List<Map<String,Object>> financialDocuments(){return service.financialDocuments();}
 @GetMapping("/financial-statement") public Map<String,Object> financialStatement(){return service.financialStatement();}
 @GetMapping(value="/financial-statement/pdf",produces=MediaType.APPLICATION_PDF_VALUE) public ResponseEntity<byte[]> financialStatementPdf(){byte[] body=service.financialStatementPdf();return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(body.length).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("customer-statement.pdf").build().toString()).body(body);}
 @GetMapping("/shipments/{id}/document-centre") public List<Map<String,Object>> documentCentre(@PathVariable UUID id){return service.documentCentre(id);}
}
