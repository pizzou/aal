package com.logiplatform.controller;

import com.logiplatform.service.ProductionPhaseReadinessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/production/phases")
@PreAuthorize("hasRole('ADMIN')")
public class ProductionPhaseReadinessController {
    private final ProductionPhaseReadinessService service;
    public ProductionPhaseReadinessController(ProductionPhaseReadinessService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<Map<String, Object>> report() {
        Map<String, Object> result = service.report();
        return ResponseEntity.status(Boolean.TRUE.equals(result.get("ready")) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }
}
