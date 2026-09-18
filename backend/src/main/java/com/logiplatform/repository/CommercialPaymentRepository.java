package com.logiplatform.repository;

import com.logiplatform.model.CommercialPayment;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CommercialPaymentRepository extends JpaRepository<CommercialPayment,UUID> {
    Optional<CommercialPayment> findByTenantIdAndIdempotencyKey(UUID tenantId,String idempotencyKey);
}
