package com.logiplatform.repository;

import com.logiplatform.model.RailConsignment;


import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RailConsignmentRepository extends JpaRepository<RailConsignment,UUID>{List<RailConsignment> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);}
