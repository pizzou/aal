package com.logiplatform.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_items")
public class InventoryItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand = 0;

    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock. Concurrent stock adjustments on the same item are a real
     * production hazard at scale (two warehouse staff scanning the same SKU at
     * once) —
     * this makes a lost-update silently corrupt the count instead throw
     * OptimisticLockException so the caller retries. For very high-contention SKUs,
     * consider @Lock(PESSIMISTIC_WRITE) on the repository fetch instead (see
     * InventoryItemRepository comment) rather than relying on optimistic retry
     * alone.
     */
    @Version
    private long version;

    protected InventoryItem() {
    }

    public InventoryItem(UUID tenantId, UUID warehouseId, String sku, String name, int reorderLevel) {
        this.tenantId = tenantId;
        this.warehouseId = warehouseId;
        this.sku = sku;
        this.name = name;
        this.reorderLevel = reorderLevel;
    }

    /**
     * Applies a signed stock movement. Callers must use StockMovementService so
     * every change is persisted together with a stock-movement ledger entry.
     */
    public void applyMovement(int delta) {
        int newQuantity = this.quantityOnHand + delta;
        if (newQuantity < 0) {
            throw new IllegalStateException(
                    "Movement would result in negative stock for SKU " + sku +
                            " (current: " + quantityOnHand + ", delta: " + delta + ")");
        }
        this.quantityOnHand = newQuantity;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getQuantityOnHand() {
        return quantityOnHand;
    }

    public int getReorderLevel() {
        return reorderLevel;
    }

    public boolean isBelowReorderLevel() {
        return quantityOnHand <= reorderLevel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
