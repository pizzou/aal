package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresses the dashboard worker thread tenant scope / RLS isolation defect.
 */
class AdvancedDashboardTenantContextTest {

    @Test
    void workerSeesRequestedTenantAndClearsThreadAfterSuccess() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new AdvancedDashboardService(new JdbcTemplate(), executor);
            UUID tenant = UUID.randomUUID();
            Future<UUID> result = executor.submit(() -> service.withTenant(tenant, TenantContext::getTenantId));
            assertEquals(tenant, result.get());
            assertFalse(executor.submit((java.util.concurrent.Callable<Boolean>) TenantContext::isSet).get(),
                    "Tenant scope must never leak to another dashboard request");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workerClearsTenantEvenWhenAggregateThrows() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new AdvancedDashboardService(new JdbcTemplate(), executor);
            Future<Boolean> result = executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> service.withTenant(
                        UUID.randomUUID(), () -> {
                            throw new IllegalStateException("Simulated query failure");
                        }));
                return TenantContext.isSet();
            });
            assertFalse(result.get(), "The failing worker must also clear tenant scope");
        } finally {
            executor.shutdownNow();
        }
    }
}
