package com.logiplatform.repository;
import com.logiplatform.model.UserNotification;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;
public interface UserNotificationRepository extends JpaRepository<UserNotification,UUID>{
 Page<UserNotification> findAllByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId,UUID userId,Pageable pageable);
 long countByTenantIdAndUserIdAndReadFalse(UUID tenantId,UUID userId);
 java.util.Optional<UserNotification> findByIdAndTenantIdAndUserId(UUID id,UUID tenantId,UUID userId);
}
