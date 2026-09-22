package com.logiplatform.controller;

import com.logiplatform.service.DocumentSecurityService;
import com.logiplatform.service.DocumentSignatureService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/document-security")
public class DocumentSecurityController {
    private final DocumentSecurityService scanner;
    private final DocumentSignatureService signatures;

    public DocumentSecurityController(DocumentSecurityService scanner, DocumentSignatureService signatures) {
        this.scanner=scanner; this.signatures=signatures;
    }

    @PostMapping("/shipments/{shipmentId}/documents/{documentId}/scan")
    public ResponseEntity<Map<String,Object>> scan(
            @PathVariable UUID shipmentId,
            @PathVariable UUID documentId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(scanner.scan(shipmentId,documentId,file));
    }

    @PostMapping("/signatures")
    public ResponseEntity<Map<String,Object>> requestSignature(@Valid @RequestBody SignatureRequest r) {
        return ResponseEntity.ok(signatures.request(r.documentId(),r.signerName(),r.signerEmail()));
    }

    @PostMapping("/signatures/{requestId}/complete")
    public ResponseEntity<Map<String,Object>> complete(@PathVariable UUID requestId, @RequestParam(required=false) String signatureReference) {
        return ResponseEntity.ok(signatures.complete(requestId,signatureReference));
    }

    @PostMapping("/signatures/webhook")
    public ResponseEntity<Map<String,Object>> webhook(
            @RequestHeader(value="X-AAL-Signature",required=false) String signature,
            @RequestBody String payload) {
        return ResponseEntity.ok(signatures.webhook(payload,signature));
    }

    public record SignatureRequest(@NotNull UUID documentId,@NotBlank String signerName,@Email String signerEmail) {}
}
