package com.logiplatform.repository;

import com.logiplatform.model.StockMovement;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {
    Page<StockMovement> findAllByTenantIdAndInventoryItemIdOrderByCreatedAtDesc(
            UUID tenantId, UUID inventoryItemId, Pageable pageable);
}
