package com.logiplatform.service;

import com.logiplatform.model.InventoryItem;
import com.logiplatform.repository.InventoryItemRepository;
import com.logiplatform.model.MovementType;
import com.logiplatform.model.StockMovement;
import com.logiplatform.repository.StockMovementRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import static com.logiplatform.dto.WarehouseDtos.*;



/**
 * This is the ONLY class allowed to change InventoryItem.quantityOnHand. Every change
 * goes through here so the stock_movements table stays a complete, trustworthy audit
 * trail — a warehouse that can't explain "why does the count say 40" via a ledger is a
 * warehouse that will lose a client's trust the first time a stock discrepancy shows up.
 */
@Service
public class StockMovementService {

    private final InventoryItemRepository inventoryItemRepository;
    private final StockMovementRepository stockMovementRepository;

    public StockMovementService(InventoryItemRepository inventoryItemRepository,
                                 StockMovementRepository stockMovementRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    @Transactional
    public InventoryItemResponse recordMovement(UUID itemId, RecordMovementRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        MovementType type;
        try {
            type = MovementType.valueOf(request.movementType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid movementType: " + request.movementType() +
                    " (expected IN, OUT, ADJUSTMENT_IN, or ADJUSTMENT_OUT)");
        }

        // Row lock: two concurrent movements on the same item serialize here rather than
        // racing on a read-modify-write. Held only for this transaction's duration.
        InventoryItem item = inventoryItemRepository.findByIdAndTenantIdForUpdate(itemId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory item not found"));

        try {
            item.applyMovement(type.signedQuantity(request.quantity()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        inventoryItemRepository.save(item);
        stockMovementRepository.save(
                new StockMovement(tenantId, itemId, type, request.quantity(), request.reason()));

        return InventoryItemResponse.from(item);
    }

    @Transactional(readOnly = true)
    public Page<MovementResponse> history(UUID itemId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        return stockMovementRepository
                .findAllByTenantIdAndInventoryItemIdOrderByCreatedAtDesc(tenantId, itemId, pageable)
                .map(MovementResponse::from);
    }
}

