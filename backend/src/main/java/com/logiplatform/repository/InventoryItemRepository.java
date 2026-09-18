package com.logiplatform.repository;

import com.logiplatform.model.InventoryItem;
import com.logiplatform.service.StockMovementService;


import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    Optional<InventoryItem> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<InventoryItem> findAllByTenantIdAndWarehouseId(UUID tenantId, UUID warehouseId, Pageable pageable);

    boolean existsByTenantIdAndWarehouseIdAndSku(UUID tenantId, UUID warehouseId, String sku);

    /**
     * Used by StockMovementService for the actual quantity mutation. PESSIMISTIC_WRITE
     * takes a row lock (SELECT ... FOR UPDATE) so two concurrent movements on the same
     * item serialize instead of racing — safer under high contention than relying on
     * @Version/optimistic-retry alone, at the cost of a lock wait instead of a fast retry.
     * Use the plain findByIdAndTenantId for reads that don't mutate quantity.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id = :id and i.tenantId = :tenantId")
    Optional<InventoryItem> findByIdAndTenantIdForUpdate(UUID id, UUID tenantId);
}
