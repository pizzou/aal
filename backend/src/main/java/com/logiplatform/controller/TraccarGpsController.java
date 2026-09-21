package com.logiplatform.controller;

import com.logiplatform.dto.GpsDtos.GpsDeviceRequest;
import com.logiplatform.dto.GpsDtos.GpsDeviceResponse;
import com.logiplatform.dto.GpsDtos.PositionResponse;
import com.logiplatform.service.TraccarGpsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/vehicles/{vehicleId}/gps/traccar")
public class TraccarGpsController {
    private final TraccarGpsService service;

    public TraccarGpsController(TraccarGpsService service) {
        this.service = service;
    }

    @GetMapping("/device")
    public ResponseEntity<GpsDeviceResponse> device(@PathVariable UUID vehicleId) {
        GpsDeviceResponse response = service.device(vehicleId);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @PostMapping("/device")
    public ResponseEntity<GpsDeviceResponse> saveDevice(
            @PathVariable UUID vehicleId,
            @Valid @RequestBody GpsDeviceRequest request) {
        return ResponseEntity.ok(service.saveDevice(vehicleId, request));
    }

    @PostMapping("/sync")
    public ResponseEntity<PositionResponse> sync(@PathVariable UUID vehicleId) {
        return ResponseEntity.ok(service.sync(vehicleId));
    }
}
