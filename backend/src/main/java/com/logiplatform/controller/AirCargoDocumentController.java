package com.logiplatform.controller;

import com.logiplatform.service.AirCargoDocumentService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.logiplatform.dto.AirCargoDtos.*;

@RestController
@RequestMapping("/api/air-cargo/documents")
public class AirCargoDocumentController {
    private final AirCargoDocumentService service;

    public AirCargoDocumentController(AirCargoDocumentService s) {
        service = s;
    }

    @GetMapping("/shipment/{shipmentId}")
    public List<Map<String, Object>> shipmentDocuments(@PathVariable UUID shipmentId) {
        return service.documentsForShipment(shipmentId);
    }

    @PostMapping("/awb")
    public AwbResponse awb(@Valid @RequestBody AwbRequest r) {
        return service.createAwb(r);
    }

    @PostMapping("/awb/{id}/submit")
    public AwbResponse submitAwb(@PathVariable UUID id, @RequestParam String carrierReference) {
        return service.submitAwb(id, carrierReference);
    }

    @PostMapping("/awb/{id}/submit-to-carrier")
    public AwbResponse submitAwbToCarrier(@PathVariable UUID id) {
        return service.submitAwbToCarrier(id);
    }

    @PostMapping("/customs/{id}/submit-to-external")
    public Map<String, Object> submitCustomsToExternal(@PathVariable UUID id) {
        return service.submitCustomsToExternal(id);
    }

    @PostMapping("/customs")
    public Map<String, Object> customs(@Valid @RequestBody CustomsRequest r) {
        return service.createCustoms(r);
    }

    @PostMapping("/customs/{id}/submit")
    public Map<String, Object> submitCustoms(@PathVariable UUID id) {
        return service.submitCustoms(id);
    }

    @PostMapping
    public Map<String, Object> document(@Valid @RequestBody DocumentRequest r) {
        return service.createDocument(r);
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return service.templates();
    }
}
