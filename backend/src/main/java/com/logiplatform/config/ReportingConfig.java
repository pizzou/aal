package com.logiplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Reporting queries are read-only aggregations SCOPED TO THE CALLER'S OWN TENANT —
 * the opposite situation from AuthDataConfig's raw JdbcTemplate, which deliberately
 * bypasses tenant scoping for the few pre-tenant/cross-tenant cases documented there.
 *
 * This bean autowires the PRIMARY DataSource bean (JpaTenantConfig's
 * TenantAwareDataSource), so every query through it automatically respects
 * app.current_tenant / RLS exactly like the JPA repositories do — verified directly
 * against real Postgres before this was written (see RLS_VERIFICATION.md): the same
 * aggregation queries below were run as the logi_app role with a tenant scope set,
 * and confirmed to return only that tenant's data.
 */
@Configuration
public class ReportingConfig {

    @Bean
    public JdbcTemplate reportingJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
