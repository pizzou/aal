package com.logiplatform.controller;

import com.logiplatform.dto.UniversalLogisticsDtos;
import com.logiplatform.model.CargoItem;
import com.logiplatform.model.TransportPlanLeg;
import com.logiplatform.service.UniversalLogisticsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/universal")
public class UniversalLogisticsController {
    private final UniversalLogisticsService service;

    public UniversalLogisticsController(UniversalLogisticsService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return service.capabilities();
    }

    @GetMapping("/shipments/{shipmentId}/cargo")
    public List<CargoItem> cargo(@PathVariable UUID shipmentId) {
        return service.cargo(shipmentId);
    }

    @PostMapping("/shipments/{shipmentId}/cargo")
    public ResponseEntity<CargoItem> addCargo(@PathVariable UUID shipmentId,
            @Valid @RequestBody UniversalLogisticsDtos.CargoRequest request) {
        return ResponseEntity.ok(service.addCargo(shipmentId, request));
    }

    @GetMapping("/shipments/{shipmentId}/legs")
    public List<TransportPlanLeg> legs(@PathVariable UUID shipmentId) {
        return service.legs(shipmentId);
    }

    @PostMapping("/shipments/{shipmentId}/legs")
    public ResponseEntity<TransportPlanLeg> addLeg(@PathVariable UUID shipmentId,
            @Valid @RequestBody UniversalLogisticsDtos.LegRequest request) {
        return ResponseEntity.ok(service.addLeg(shipmentId, request));
    }
}
