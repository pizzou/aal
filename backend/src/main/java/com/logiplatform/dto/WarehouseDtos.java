package com.logiplatform.dto;

import com.logiplatform.model.InventoryItem;
import com.logiplatform.model.StockMovement;
import com.logiplatform.model.Warehouse;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

public final class WarehouseDtos {

    private WarehouseDtos() {}

    public record CreateWarehouseRequest(@NotBlank String name, @NotBlank String address) {}

    public record WarehouseResponse(UUID id, String name, String address, Instant createdAt) {
        public static WarehouseResponse from(Warehouse w) {
            return new WarehouseResponse(w.getId(), w.getName(), w.getAddress(), w.getCreatedAt());
        }
    }

    public record CreateInventoryItemRequest(
            @NotBlank String sku,
            @NotBlank String name,
            @PositiveOrZero int reorderLevel
    ) {}

    public record InventoryItemResponse(
            UUID id, UUID warehouseId, String sku, String name,
            int quantityOnHand, int reorderLevel, boolean belowReorderLevel, Instant updatedAt
    ) {
        public static InventoryItemResponse from(InventoryItem i) {
            return new InventoryItemResponse(
                    i.getId(), i.getWarehouseId(), i.getSku(), i.getName(),
                    i.getQuantityOnHand(), i.getReorderLevel(), i.isBelowReorderLevel(), i.getUpdatedAt());
        }
    }

    public record RecordMovementRequest(
            @NotBlank String movementType, // IN, OUT, ADJUSTMENT_IN, ADJUSTMENT_OUT
            @Positive int quantity,
            String reason
    ) {}

    public record MovementResponse(
            UUID id, String movementType, int quantity, String reason, Instant createdAt
    ) {
        public static MovementResponse from(StockMovement m) {
            return new MovementResponse(
                    m.getId(), m.getMovementType().name(), m.getQuantity(), m.getReason(), m.getCreatedAt());
        }
    }
}
