package com.logiplatform.repository;

import com.logiplatform.model.CargoItem;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface CargoItemRepository extends JpaRepository<CargoItem,UUID>{List<CargoItem> findAllByTenantIdAndShipmentIdOrderByLineNoAsc(UUID tenantId,UUID shipmentId);}
