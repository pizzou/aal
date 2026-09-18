
package com.logiplatform.repository;

import com.logiplatform.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Email is globally unique in the AAL database because authentication
     * happens before a tenant is selected.
     */
    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndTenantId(
            UUID id,
            UUID tenantId);

    List<User> findAllByTenantIdOrderByCreatedAtDesc(
            UUID tenantId);

    long countByTenantIdAndRoleAndActiveTrue(
            UUID tenantId,
            String role);

    long countByTenantIdAndActiveTrue(
            UUID tenantId);
}
