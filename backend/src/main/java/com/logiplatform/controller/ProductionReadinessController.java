package com.logiplatform.controller;

import com.logiplatform.service.ProductionReadinessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/production")
@PreAuthorize("hasRole('ADMIN')")
public class ProductionReadinessController {
    private final ProductionReadinessService readiness;

    public ProductionReadinessController(ProductionReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness(
            @RequestParam(defaultValue = "true") boolean strict) {
        Map<String, Object> result = readiness.readiness(strict);
        return ResponseEntity.status(Boolean.TRUE.equals(result.get("ready"))
                ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }
}
