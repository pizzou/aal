package com.logiplatform;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Shared tenant bootstrap for integration tests.
 *
 * Production foreign keys intentionally require every tenant-scoped entity to reference
 * an existing row in tenants. Tests therefore provision their synthetic tenant through
 * the raw authentication datasource before entering a tenant-scoped context.
 */
abstract class TenantTestSupport {

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbcTemplate;

    protected final void setTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        authJdbcTemplate.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId,
                "Test Tenant " + tenantId);

        TenantContext.setTenantId(tenantId);
    }
}
