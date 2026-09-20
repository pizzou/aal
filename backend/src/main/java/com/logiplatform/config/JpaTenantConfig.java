package com.logiplatform.config;

import com.logiplatform.tenancy.TenantAwareDataSource;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.JpaTransactionManager;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class JpaTenantConfig {

    @Bean(name = "rawDataSource")
    public HikariDataSource rawDataSource(
            DataSourceProperties properties,
            Environment environment) {

        String configuredUrl = properties.getUrl();

        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalStateException(
                    "spring.datasource.url is required for the AAL PostgreSQL datasource");
        }

        if (configuredUrl.contains("${")) {
            throw new IllegalStateException(
                    "spring.datasource.url contains an unresolved environment placeholder. "
                            + "Set SPRING_DATASOURCE_URL to the actual PostgreSQL JDBC URL.");
        }

        String jdbcUrl = configuredUrl.trim();
        String username = properties.getUsername();
        String password = properties.getPassword();

        if (jdbcUrl.startsWith("postgresql://") || jdbcUrl.startsWith("postgres://")) {
            try {
                URI uri = URI.create(jdbcUrl);
                String userInfo = uri.getUserInfo();
                if ((username == null || username.isBlank()) && userInfo != null && !userInfo.isBlank()) {
                    String[] credentials = userInfo.split(":", 2);
                    username = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
                    if ((password == null || password.isBlank()) && credentials.length == 2) {
                        password = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8);
                    }
                }
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                String query = uri.getRawQuery();
                jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path
                        + (query == null || query.isBlank() ? "" : "?" + query);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "DATABASE_URL is not a valid PostgreSQL connection URI", ex);
            }
        }

        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                    "Unsupported datasource URL for AAL. "
                            + "This application requires PostgreSQL (jdbc:postgresql:...)");
        }

        HikariDataSource dataSource = new HikariDataSource();

        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("org.postgresql.Driver");

        dataSource.setMaximumPoolSize(
                getInt(
                        environment,
                        "spring.datasource.hikari.maximum-pool-size",
                        20));

        dataSource.setMinimumIdle(
                getInt(
                        environment,
                        "spring.datasource.hikari.minimum-idle",
                        5));

        dataSource.setConnectionTimeout(
                getLong(
                        environment,
                        "spring.datasource.hikari.connection-timeout",
                        5000L));

        dataSource.setValidationTimeout(
                getLong(
                        environment,
                        "spring.datasource.hikari.validation-timeout",
                        2000L));

        dataSource.setMaxLifetime(
                getLong(
                        environment,
                        "spring.datasource.hikari.max-lifetime",
                        1800000L));

        dataSource.setLeakDetectionThreshold(
                getLong(
                        environment,
                        "spring.datasource.hikari.leak-detection-threshold",
                        0L));

        String poolName = environment.getProperty(
                "spring.datasource.hikari.pool-name",
                "LogiPlatformHikariPool");

        dataSource.setPoolName(poolName);

        return dataSource;
    }

    /**
     * Primary datasource used by JPA and tenant-scoped JDBC operations.
     *
     * TenantAwareDataSource applies the current tenant to each physical
     * connection before it is used.
     */
    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("rawDataSource") DataSource rawDataSource) {

        return new TenantAwareDataSource(rawDataSource);
    }

    /**
     * Tenant-scoped JDBC access. Unlike authJdbcTemplate, this template uses the
     * TenantAwareDataSource so PostgreSQL RLS remains the database-level backstop.
     */
    @Bean(name = "tenantJdbcTemplate")
    public JdbcTemplate tenantJdbcTemplate(
            @Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Primary transaction manager for JPA repositories and tenant-scoped domain services.
     * Authentication JDBC operations deliberately use authTransactionManager from AuthDataConfig.
     */
    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    private static int getInt(
            Environment environment,
            String property,
            int defaultValue) {

        String value = environment.getProperty(property);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                    "Invalid integer value for " + property + ": " + value,
                    ex);
        }
    }

    private static long getLong(
            Environment environment,
            String property,
            long defaultValue) {

        String value = environment.getProperty(property);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                    "Invalid numeric value for " + property + ": " + value,
                    ex);
        }
    }

}
