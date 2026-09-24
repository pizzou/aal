package com.logiplatform.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Narrow single-tenant database access for token-scoped public endpoints.
 *
 * Public quote/tracking requests arrive without a tenant context, so they
 * cannot use the tenant-aware datasource for the initial token lookup. The
 * public services constrain every lookup by an unguessable token hash and,
 * where relevant, the stored tenant id before touching business data.
 */
@Configuration
public class PublicDataConfig {

    @Bean(name = "publicJdbcTemplate")
    public JdbcTemplate publicJdbcTemplate(
            @Qualifier("singleTenantDataSource") DataSource singleTenantDataSource) {
        return new JdbcTemplate(singleTenantDataSource);
    }

    @Bean(name = "publicTransactionManager")
    public PlatformTransactionManager publicTransactionManager(
            @Qualifier("singleTenantDataSource") DataSource singleTenantDataSource) {
        return new DataSourceTransactionManager(singleTenantDataSource);
    }
}
