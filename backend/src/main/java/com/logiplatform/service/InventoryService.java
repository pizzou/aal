package com.logiplatform.service;

import com.logiplatform.model.InventoryItem;
import com.logiplatform.repository.InventoryItemRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import static com.logiplatform.dto.WarehouseDtos.*;



@Service
public class InventoryService {

    private final InventoryItemRepository inventoryItemRepository;
    private final WarehouseService warehouseService;

    public InventoryService(InventoryItemRepository inventoryItemRepository, WarehouseService warehouseService) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.warehouseService = warehouseService;
    }

    @Transactional
    public InventoryItemResponse create(UUID warehouseId, CreateInventoryItemRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        warehouseService.getOwned(warehouseId); // 404s if warehouse doesn't belong to this tenant

        if (inventoryItemRepository.existsByTenantIdAndWarehouseIdAndSku(tenantId, warehouseId, request.sku())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "SKU '" + request.sku() + "' already exists in this warehouse");
        }

        InventoryItem item = new InventoryItem(
                tenantId, warehouseId, request.sku(), request.name(), request.reorderLevel());
        return InventoryItemResponse.from(inventoryItemRepository.save(item));
    }

    @Transactional(readOnly = true)
    public Page<InventoryItemResponse> list(UUID warehouseId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        warehouseService.getOwned(warehouseId);
        return inventoryItemRepository.findAllByTenantIdAndWarehouseId(tenantId, warehouseId, pageable)
                .map(InventoryItemResponse::from);
    }

    @Transactional(readOnly = true)
    public InventoryItemResponse get(UUID itemId) {
        UUID tenantId = TenantContext.getTenantId();
        InventoryItem item = inventoryItemRepository.findByIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory item not found"));
        return InventoryItemResponse.from(item);
    }
}

