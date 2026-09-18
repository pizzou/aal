package com.logiplatform.repository;

import com.logiplatform.model.RoadConsignment;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RoadConsignmentRepository extends JpaRepository<RoadConsignment,UUID>{List<RoadConsignment> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);}
