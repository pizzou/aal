package com.logiplatform.repository;

import com.logiplatform.model.RateCard;


import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateCardRepository extends JpaRepository<RateCard, UUID> {
    List<RateCard> findAllByTenantId(UUID tenantId);
    Optional<RateCard> findByTenantIdAndTransportMode(UUID tenantId, String transportMode);
}
