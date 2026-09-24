package com.logiplatform.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Authentication and registration operate before the request has a tenant
 * context. They therefore use the fixed single-tenant datasource.
 *
 * This datasource must remain narrowly scoped to authentication/provisioning
 * operations and must not be used for tenant-wide business queries.
 */
@Configuration
public class AuthDataConfig {

    @Bean(name = "authJdbcTemplate")
    public JdbcTemplate authJdbcTemplate(
            @Qualifier("singleTenantDataSource") DataSource singleTenantDataSource) {

        return new JdbcTemplate(singleTenantDataSource);
    }

    /**
     * Dedicated transaction manager for pre-authentication JDBC operations.
     *
     * This is important for:
     * - SELECT ... FOR UPDATE during login
     * - atomic tenant/user provisioning during registration
     * - token-version updates during logout
     */
    @Bean(name = "authTransactionManager")
    public PlatformTransactionManager authTransactionManager(
            @Qualifier("singleTenantDataSource") DataSource singleTenantDataSource) {

        return new DataSourceTransactionManager(singleTenantDataSource);
    }
}