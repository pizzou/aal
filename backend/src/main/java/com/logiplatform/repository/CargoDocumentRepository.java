package com.logiplatform.repository;

import com.logiplatform.model.CargoDocument;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface CargoDocumentRepository extends JpaRepository<CargoDocument,UUID>{List<CargoDocument> findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(UUID t,UUID s);}
