package com.logiplatform.controller;

import com.logiplatform.service.InventoryService;
import com.logiplatform.service.StockMovementService;


import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import static com.logiplatform.dto.WarehouseDtos.*;



@RestController
public class InventoryController {

    private final InventoryService inventoryService;
    private final StockMovementService stockMovementService;

    public InventoryController(InventoryService inventoryService, StockMovementService stockMovementService) {
        this.inventoryService = inventoryService;
        this.stockMovementService = stockMovementService;
    }

    @PostMapping("/api/warehouses/{warehouseId}/inventory")
    public ResponseEntity<InventoryItemResponse> create(
            @PathVariable UUID warehouseId, @Valid @RequestBody CreateInventoryItemRequest request) {
        return ResponseEntity.ok(inventoryService.create(warehouseId, request));
    }

    @GetMapping("/api/warehouses/{warehouseId}/inventory")
    public ResponseEntity<Page<InventoryItemResponse>> list(
            @PathVariable UUID warehouseId, Pageable pageable) {
        return ResponseEntity.ok(inventoryService.list(warehouseId, pageable));
    }

    @GetMapping("/api/inventory/{itemId}")
    public ResponseEntity<InventoryItemResponse> get(@PathVariable UUID itemId) {
        return ResponseEntity.ok(inventoryService.get(itemId));
    }

    @PostMapping("/api/inventory/{itemId}/movements")
    public ResponseEntity<InventoryItemResponse> recordMovement(
            @PathVariable UUID itemId, @Valid @RequestBody RecordMovementRequest request) {
        return ResponseEntity.ok(stockMovementService.recordMovement(itemId, request));
    }

    @GetMapping("/api/inventory/{itemId}/movements")
    public ResponseEntity<Page<MovementResponse>> history(@PathVariable UUID itemId, Pageable pageable) {
        return ResponseEntity.ok(stockMovementService.history(itemId, pageable));
    }
}

