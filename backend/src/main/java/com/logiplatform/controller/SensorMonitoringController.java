package com.logiplatform.controller;

import com.logiplatform.service.SensorMonitoringService;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import static com.logiplatform.dto.SensorDtos.*;

@RestController
@RequestMapping("/api/shipments/{shipmentId}/sensors")
public class SensorMonitoringController {

    private final SensorMonitoringService sensorMonitoringService;

    public SensorMonitoringController(SensorMonitoringService sensorMonitoringService) {
        this.sensorMonitoringService = sensorMonitoringService;
    }

    @PostMapping("/readings")
    public ResponseEntity<ReadingResponse> recordReading(
            @PathVariable UUID shipmentId, @Valid @RequestBody RecordReadingRequest request) {
        return ResponseEntity.ok(sensorMonitoringService.recordReading(shipmentId, request));
    }

    @GetMapping("/readings")
    public ResponseEntity<Page<ReadingResponse>> history(@PathVariable UUID shipmentId, Pageable pageable) {
        return ResponseEntity.ok(sensorMonitoringService.history(shipmentId, pageable));
    }

    @PutMapping("/threshold")
    public ResponseEntity<ThresholdResponse> setThreshold(
            @PathVariable UUID shipmentId, @Valid @RequestBody SetThresholdRequest request) {
        return ResponseEntity.ok(sensorMonitoringService.setThreshold(shipmentId, request));
    }
}
