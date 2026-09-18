package com.logiplatform.controller;

import com.logiplatform.service.WarehouseService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import static com.logiplatform.dto.WarehouseDtos.*;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody CreateWarehouseRequest request) {
        return ResponseEntity.ok(warehouseService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<WarehouseResponse>> list() {
        return ResponseEntity.ok(warehouseService.list());
    }
}
