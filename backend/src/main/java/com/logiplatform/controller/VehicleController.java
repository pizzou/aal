package com.logiplatform.controller;

import com.logiplatform.service.VehicleService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import static com.logiplatform.dto.TmsDtos.*;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody CreateVehicleRequest request) {
        return ResponseEntity.ok(vehicleService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<VehicleResponse>> list() {
        return ResponseEntity.ok(vehicleService.list());
    }
}
