package com.logiplatform.controller;

import com.logiplatform.service.OperationsEventStreamService;
import com.logiplatform.security.TenantPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/operations/events")
public class OperationsEventStreamController {
    private final OperationsEventStreamService stream;
    public OperationsEventStreamController(OperationsEventStreamService stream) { this.stream = stream; }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof TenantPrincipal tp)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
        return stream.subscribe(tp.tenantId());
    }
}
