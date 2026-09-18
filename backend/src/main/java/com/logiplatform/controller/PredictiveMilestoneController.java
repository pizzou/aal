package com.logiplatform.controller;

import com.logiplatform.service.PredictiveMilestoneService;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/flight-status")
public class PredictiveMilestoneController {
    private final PredictiveMilestoneService s;

    public PredictiveMilestoneController(PredictiveMilestoneService s) {
        this.s = s;
    }

    @GetMapping("/shipments/{shipmentId}/prediction")
    public PredictiveMilestoneService.Prediction predict(@PathVariable UUID shipmentId) {
        return s.predict(shipmentId);
    }
}
