package com.logiplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("prod")
public class ProductionConfigurationValidator {

    public ProductionConfigurationValidator(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${spring.datasource.username}") String dbUser,
            @Value("${spring.datasource.password}") String dbPassword,
            @Value("${security.cors.allowed-origins}") String origins,
            @Value("${app.environment}") String environment) {

        if (jwtSecret == null
                || jwtSecret.isBlank()
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || jwtSecret.contains("dev-only")
                || jwtSecret.contains("change-me")) {

            throw new IllegalStateException(
                    "Production requires a strong JWT_SECRET of at least 32 bytes");
        }

        if (dbUser == null
                || dbUser.isBlank()
                || dbUser.equalsIgnoreCase("postgres")
                || dbUser.equalsIgnoreCase("logi")) {

            throw new IllegalStateException(
                    "Production must use a dedicated least-privilege application database role");
        }

        if (dbPassword == null
                || dbPassword.isBlank()
                || dbPassword.contains("change-me")
                || dbPassword.contains("dev_pw")
                || dbPassword.equals("Mpumuro@2025")) {

            throw new IllegalStateException(
                    "Production database password is not configured with a production secret");
        }

        if (origins == null
                || origins.isBlank()
                || origins.contains("localhost")
                || origins.contains("*")) {

            throw new IllegalStateException(
                    "Production CORS must contain only explicit trusted origins");
        }

        if (environment == null
                || !"production".equalsIgnoreCase(environment.trim())) {

            throw new IllegalStateException(
                    "Production profile requires app.environment=production");
        }
    }
}