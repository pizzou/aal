package com.logiplatform.controller;

import com.logiplatform.service.AwbDocumentService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/shipments/{shipmentId}/documents")
public class DocumentController {

    private final AwbDocumentService awbDocumentService;

    public DocumentController(AwbDocumentService awbDocumentService) {
        this.awbDocumentService = awbDocumentService;
    }

    @GetMapping("/awb")
    public ResponseEntity<byte[]> generateAwb(@PathVariable UUID shipmentId) {
        byte[] pdf = awbDocumentService.generateAwb(shipmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"awb-" + shipmentId + ".pdf\"")
                .body(pdf);
    }
}
