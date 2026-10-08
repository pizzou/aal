package com.logiplatform.repository;

import com.logiplatform.model.CommercialPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CommercialPaymentAllocationRepository extends JpaRepository<CommercialPaymentAllocation, UUID> {
    List<CommercialPaymentAllocation> findAllByTenantIdAndPaymentIdOrderByPercentageDesc(UUID tenantId, UUID paymentId);
}
