package com.logiplatform.repository;

import com.logiplatform.model.Notification;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(UUID tenantId, UUID shipmentId, Pageable pageable);
}
