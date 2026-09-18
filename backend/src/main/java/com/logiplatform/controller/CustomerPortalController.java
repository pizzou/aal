package com.logiplatform.controller;
import com.logiplatform.dto.CustomerPortalDtos.*;
import com.logiplatform.service.CustomerPortalService;
import org.springframework.web.bind.annotation.*;
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
}
