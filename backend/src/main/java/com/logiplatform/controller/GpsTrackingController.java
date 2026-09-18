package com.logiplatform.controller;

import com.logiplatform.service.GpsTrackingService;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import static com.logiplatform.dto.GpsDtos.*;

@RestController
@RequestMapping("/api/vehicles/{vehicleId}/gps")
public class GpsTrackingController {

    private final GpsTrackingService gpsTrackingService;

    public GpsTrackingController(GpsTrackingService gpsTrackingService) {
        this.gpsTrackingService = gpsTrackingService;
    }

    @PostMapping
    public ResponseEntity<PositionResponse> recordPosition(
            @PathVariable UUID vehicleId, @Valid @RequestBody RecordPositionRequest request) {
        return ResponseEntity.ok(gpsTrackingService.recordPosition(vehicleId, request));
    }

    @GetMapping("/latest")
    public ResponseEntity<PositionResponse> latest(@PathVariable UUID vehicleId) {
        return ResponseEntity.ok(gpsTrackingService.latest(vehicleId));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<PositionResponse>> history(@PathVariable UUID vehicleId, Pageable pageable) {
        return ResponseEntity.ok(gpsTrackingService.history(vehicleId, pageable));
    }
}
