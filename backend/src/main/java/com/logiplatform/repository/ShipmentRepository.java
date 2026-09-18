package com.logiplatform.repository;

import com.logiplatform.model.Shipment;
import com.logiplatform.model.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
    Optional<Shipment> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<Shipment> findAllByTenantId(UUID tenantId, Pageable pageable);
    List<Shipment> findAllByTenantIdAndWeightKgIsNotNullAndStatus(UUID tenantId, ShipmentStatus status);
    boolean existsByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);
    Optional<Shipment> findByTenantIdAndReferenceCode(UUID tenantId, String referenceCode);
    List<Shipment> findAllByTenantIdAndEtdGreaterThanEqualAndEtdLessThanOrderByEtdAsc(UUID tenantId, Instant from, Instant to);
    List<Shipment> findAllByTenantIdAndNextActionDateOrderByNextActionDateAsc(UUID tenantId, LocalDate date);
    List<Shipment> findAllByTenantIdAndDateOpenedGreaterThanEqualAndDateOpenedLessThan(
            UUID tenantId, LocalDate fromInclusive, LocalDate toExclusive);
}
