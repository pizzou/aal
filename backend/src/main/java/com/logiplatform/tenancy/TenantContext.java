package com.logiplatform.tenancy;

import java.util.UUID;

/**
 * Holds the current request's tenant ID on a thread-local.
 * Set by JwtAuthenticationFilter after token validation, cleared at the end of every request.
 *
 * This is the SINGLE SOURCE OF TRUTH the application layer uses to scope every query.
 * It is backstopped, not replaced, by Postgres Row-Level Security (see V2__enable_rls.sql) —
 * if application code ever forgets to scope a query by tenant, RLS still blocks the leak
 * as long as TenantAwareDataSource has set app.current_tenant on the connection.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID getTenantId() {
        UUID tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "No tenant set on this thread. This method must only be called for " +
                "authenticated, tenant-scoped requests — check that JwtAuthenticationFilter ran."
            );
        }
        return tenantId;
    }

    public static boolean isSet() {
        return CURRENT_TENANT.get() != null;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
