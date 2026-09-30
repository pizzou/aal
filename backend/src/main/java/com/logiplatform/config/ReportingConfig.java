package com.logiplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Read-only/reporting JDBC access. The datasource remains TenantAwareDataSource,
 * so every connection receives the caller's tenant scope before SQL executes.
 */
@Configuration
public class ReportingConfig {

    @Bean
    public JdbcTemplate reportingJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * The control-tower snapshot is assembled from a bounded set of aggregate
     * queries. Give this template its own JDBC statement timeout so one pathological
     * query cannot hold the HTTP request open indefinitely.
     */
    @Bean(name = "dashboardJdbcTemplate")
    public JdbcTemplate dashboardJdbcTemplate(
            DataSource dataSource,
            @Value("${dashboard.query-timeout-seconds:5}") int queryTimeoutSeconds) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(Math.max(1, Math.min(queryTimeoutSeconds, 30)));
        return template;
    }
}
