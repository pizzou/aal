package com.logiplatform.controller;

import com.logiplatform.service.FlightDelayCheckService;
import com.logiplatform.service.FlightStatusPort.FlightStatusResult;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/shipments/{shipmentId}/flight-status")
public class FlightStatusController {

    private final FlightDelayCheckService flightDelayCheckService;

    public FlightStatusController(FlightDelayCheckService flightDelayCheckService) {
        this.flightDelayCheckService = flightDelayCheckService;
    }

    @GetMapping
    public ResponseEntity<FlightStatusResult> check(@PathVariable UUID shipmentId) {
        return ResponseEntity.ok(flightDelayCheckService.checkAndFlagDelay(shipmentId));
    }
}
