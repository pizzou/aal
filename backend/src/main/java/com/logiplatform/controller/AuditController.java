package com.logiplatform.controller;

import com.logiplatform.dto.AuditDtos.Response;
import com.logiplatform.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public Page<Response> list(Pageable pageable) {
        return service.list(pageable);
    }
}
