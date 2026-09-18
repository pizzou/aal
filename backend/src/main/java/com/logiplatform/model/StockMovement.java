package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false)
    private MovementType movementType;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected StockMovement() {}

    public StockMovement(UUID tenantId, UUID inventoryItemId, MovementType type, int quantity, String reason) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Movement quantity must be positive; direction is set by movementType");
        }
        this.tenantId = tenantId;
        this.inventoryItemId = inventoryItemId;
        this.movementType = type;
        this.quantity = quantity;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public MovementType getMovementType() { return movementType; }
    public int getQuantity() { return quantity; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
