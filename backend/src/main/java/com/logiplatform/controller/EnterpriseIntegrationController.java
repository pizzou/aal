package com.logiplatform.controller;

import com.logiplatform.integration.EnterpriseIntegrationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations/enterprise")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','FINANCE')")
public class EnterpriseIntegrationController {

    private final EnterpriseIntegrationService service;

    public EnterpriseIntegrationController(EnterpriseIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(service.health());
    }

    @GetMapping("/dcsa/{carrierCode}/{equipmentReference}")
    public ResponseEntity<Map<String, Object>> dcsa(
            @PathVariable String carrierCode,
            @PathVariable String equipmentReference) {
        return ResponseEntity.ok(service.dcsaTrack(carrierCode, equipmentReference));
    }

    @GetMapping("/ports/{portCode}/{equipmentReference}")
    public ResponseEntity<Map<String, Object>> portEvents(
            @PathVariable String portCode,
            @PathVariable String equipmentReference) {
        return ResponseEntity.ok(service.portEvents(portCode, equipmentReference));
    }

    @PostMapping("/customs/submit")
    public ResponseEntity<Map<String, Object>> customs(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(service.customsSubmit(payload));
    }

    @PostMapping("/accounting/post")
    public ResponseEntity<Map<String, Object>> accounting(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(service.accountingPost(payload));
    }

    @PostMapping("/whatsapp")
    public ResponseEntity<Map<String, Object>> whatsapp(@RequestBody WhatsAppRequest request) {
        return ResponseEntity.ok(service.sendWhatsApp(request.phoneNumber(), request.message()));
    }

    @PostMapping("/edi/build")
    public ResponseEntity<Map<String, Object>> buildEdi(@RequestBody EdiBuildRequest request) {
        return ResponseEntity.ok(service.buildEdi(
                request.standard(), request.messageType(), request.controlReference(), request.segments()));
    }

    @PostMapping("/edi/send")
    public ResponseEntity<Map<String, Object>> sendEdi(@RequestBody EdiSendRequest request) {
        return ResponseEntity.ok(service.sendEdi(request.payload(), request.messageType(), request.idempotencyKey()));
    }

    @PostMapping("/webhooks/deliver")
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody WebhookRequest request) {
        return ResponseEntity.ok(service.deliverWebhook(request.eventType(), request.targetUrl(), request.payload(), request.idempotencyKey()));
    }

    @PostMapping("/edi/parse")
    public ResponseEntity<Map<String, Object>> parseEdi(@RequestBody EdiParseRequest request) {
        return ResponseEntity.ok(service.parseEdi(request.payload()));
    }

    public record WhatsAppRequest(@NotBlank String phoneNumber, @NotBlank String message) {}
    public record EdiBuildRequest(
            String standard,
            String messageType,
            String controlReference,
            List<List<String>> segments) {}
    public record EdiParseRequest(@NotBlank String payload) {}
    public record EdiSendRequest(@NotBlank String payload, String messageType, String idempotencyKey) {}
    public record WebhookRequest(@NotBlank String eventType, @NotBlank String targetUrl, Map<String,Object> payload, String idempotencyKey) {}
}
