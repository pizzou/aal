package com.logiplatform.controller;

import com.logiplatform.service.LoadPlanningService;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import static com.logiplatform.dto.LoadPlanDtos.*;



@RestController
@RequestMapping("/api/vehicles/{vehicleId}/load-plan")
public class LoadPlanningController {

    private final LoadPlanningService loadPlanningService;

    public LoadPlanningController(LoadPlanningService loadPlanningService) {
        this.loadPlanningService = loadPlanningService;
    }

    @GetMapping
    public ResponseEntity<LoadPlanResponse> planLoad(@PathVariable UUID vehicleId) {
        return ResponseEntity.ok(loadPlanningService.planLoad(vehicleId));
    }
}

