package com.logiplatform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for Flyway startup on standalone Render deployments. */
class FlywayRenderConfigurationTest {

    @Test
    void flywayUsesUnwrappedDatabaseConnectionRatherThanTenantAwareDatasource() throws Exception {
        Method raw = JpaTenantConfig.class.getDeclaredMethod(
                "rawDataSource", DataSourceProperties.class, Environment.class);
        assertTrue(raw.isAnnotationPresent(FlywayDataSource.class),
                "Flyway must use the raw connection before TenantContext is established");
        assertEquals(HikariDataSource.class, raw.getReturnType());

        Method tenant = JpaTenantConfig.class.getDeclaredMethod("dataSource", DataSource.class);
        assertFalse(tenant.isAnnotationPresent(FlywayDataSource.class),
                "Never run schema migrations through the tenant-aware connection");
    }

    @Test
    void productionProfileDoesNotRequireComposeOnlyFlywayVariables() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("application-prod.yml")) {
            assertNotNull(stream, "Production profile must be on the runtime classpath");
            String production = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(production.contains("url: ${SPRING_FLYWAY_URL:"));
            assertFalse(production.contains("user: ${SPRING_FLYWAY_USER}"));
            assertFalse(production.contains("password: ${SPRING_FLYWAY_PASSWORD}"));
            assertTrue(production.contains("${DATABASE_URL:}"),
                    "Render DATABASE_URL must remain a usable datasource fallback");
            assertTrue(production.contains("clean-disabled: true"));
        }
    }
}
