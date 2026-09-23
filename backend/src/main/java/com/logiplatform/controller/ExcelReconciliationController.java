package com.logiplatform.controller;

import com.logiplatform.service.ExcelReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/operations/excel-reconciliation")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','OPERATIONS')")
public class ExcelReconciliationController {
    private final ExcelReconciliationService service;

    public ExcelReconciliationController(ExcelReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> latest() {
        return ResponseEntity.ok(service.latest());
    }
}
