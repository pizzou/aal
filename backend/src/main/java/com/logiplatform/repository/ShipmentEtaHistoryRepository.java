package com.logiplatform.repository;

import com.logiplatform.model.ShipmentEtaHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface ShipmentEtaHistoryRepository extends JpaRepository<ShipmentEtaHistory,UUID>{
    List<ShipmentEtaHistory> findAllByTenantIdAndShipmentIdOrderByObservedAtDesc(UUID tenantId,UUID shipmentId);
}
