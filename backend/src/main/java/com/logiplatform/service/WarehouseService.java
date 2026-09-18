package com.logiplatform.service;

import com.logiplatform.model.Warehouse;
import com.logiplatform.repository.WarehouseRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import static com.logiplatform.dto.WarehouseDtos.*;



@Service
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public WarehouseService(WarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional
    public WarehouseResponse create(CreateWarehouseRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (warehouseRepository.existsByTenantIdAndName(tenantId, request.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A warehouse named '" + request.name() + "' already exists");
        }
        Warehouse saved = warehouseRepository.save(new Warehouse(tenantId, request.name(), request.address()));
        return WarehouseResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list() {
        UUID tenantId = TenantContext.getTenantId();
        return warehouseRepository.findAllByTenantId(tenantId).stream()
                .map(WarehouseResponse::from)
                .toList();
    }

    /** Package-visible: used by InventoryService to validate a warehouse belongs to this tenant. */
    Warehouse getOwned(UUID warehouseId) {
        UUID tenantId = TenantContext.getTenantId();
        return warehouseRepository.findByIdAndTenantId(warehouseId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Warehouse not found"));
    }
}

