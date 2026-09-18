package com.logiplatform.repository;

import com.logiplatform.model.Warehouse;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    List<Warehouse> findAllByTenantId(UUID tenantId);
    Optional<Warehouse> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
