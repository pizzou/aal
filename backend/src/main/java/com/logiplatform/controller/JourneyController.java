package com.logiplatform.controller;

import com.logiplatform.dto.AdvancedEnterpriseDtos.JourneyCreateRequest;
import com.logiplatform.dto.JourneyDtos.Journey;
import com.logiplatform.service.JourneyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/shipments")
public class JourneyController {
    private final JourneyService service;

    public JourneyController(JourneyService service) {
        this.service = service;
    }

    @GetMapping("/{id}/journey")
    public Journey journey(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/journey")
    public Journey createOrUpdate(
            @PathVariable UUID id,
            @Valid @RequestBody JourneyCreateRequest request) {
        return service.createOrUpdate(id, request);
    }

    @PostMapping("/{id}/journey/refresh")
    public Journey refresh(@PathVariable UUID id) {
        return service.refresh(id);
    }
}
