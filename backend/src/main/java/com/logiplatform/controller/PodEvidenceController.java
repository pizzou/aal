package com.logiplatform.controller;

import com.logiplatform.service.PodEvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

/** All methods require session authorization and tenant context via /api/operations/**. */
@RestController
@RequestMapping("/api/operations/pod-evidence")
public class PodEvidenceController {
    private final PodEvidenceService service;
    public PodEvidenceController(PodEvidenceService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PodEvidenceService.UploadedEvidence upload(@RequestParam UUID shipmentId,
                                                     @RequestPart("file") MultipartFile file) {
        return service.upload(shipmentId, file);
    }

    @GetMapping("/{evidenceId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID evidenceId) {
        var evidence = service.download(evidenceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(evidence.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + evidence.filename() + "\"")
                .body(evidence.content());
    }
}
