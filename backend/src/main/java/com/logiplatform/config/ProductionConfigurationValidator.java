package com.logiplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;

@Configuration
@Profile("prod")
public class ProductionConfigurationValidator {

    public ProductionConfigurationValidator(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${spring.datasource.url}") String dbUrl,
            @Value("${spring.datasource.username}") String dbUser,
            @Value("${spring.datasource.password}") String dbPassword,
            @Value("${security.cors.allowed-origins}") String origins,
            @Value("${app.environment}") String environment,
            @Value("${app.frontend.url}") String frontendUrl,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${app.mail.brevo-api-key:}") String brevoApiKey,
            @Value("${app.mail.brevo-url:https://api.brevo.com/v3/smtp/email}") String brevoUrl,
            @Value("${app.mail.from:}") String mailFrom,
            @Value("${app.auth.otp.required:true}") boolean otpRequired) {

        if (jwtSecret == null
                || jwtSecret.isBlank()
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || jwtSecret.contains("dev-only")
                || jwtSecret.contains("change-me")) {

            throw new IllegalStateException(
                    "Production requires a strong JWT_SECRET of at least 32 bytes");
        }

        if (dbUrl == null
                || dbUrl.isBlank()
                || dbUrl.contains("${")) {

            throw new IllegalStateException(
                    "Production requires SPRING_DATASOURCE_URL (or DATABASE_URL) to be configured");
        }

        boolean postgresJdbc = dbUrl.startsWith("jdbc:postgresql:");
        boolean postgresUri = dbUrl.startsWith("postgresql://") || dbUrl.startsWith("postgres://");
        if (!postgresJdbc && !postgresUri) {
            throw new IllegalStateException(
                    "Production requires a PostgreSQL datasource URL");
        }

        String effectiveDbUser = dbUser;
        String effectiveDbPassword = dbPassword;
        if (postgresUri) {
            try {
                String userInfo = URI.create(dbUrl).getUserInfo();
                if ((effectiveDbUser == null || effectiveDbUser.isBlank()) && userInfo != null) {
                    String[] credentials = userInfo.split(":", 2);
                    effectiveDbUser = credentials[0];
                    if ((effectiveDbPassword == null || effectiveDbPassword.isBlank()) && credentials.length == 2) {
                        effectiveDbPassword = credentials[1];
                    }
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Production DATABASE_URL is not a valid PostgreSQL URI", ex);
            }
        }

        if (effectiveDbUser == null
                || effectiveDbUser.isBlank()
                || effectiveDbUser.equalsIgnoreCase("postgres")
                || effectiveDbUser.equalsIgnoreCase("logi")) {

            throw new IllegalStateException(
                    "Production must use a dedicated least-privilege application database role");
        }

        if (effectiveDbPassword == null
                || effectiveDbPassword.isBlank()
                || effectiveDbPassword.contains("change-me")
                || effectiveDbPassword.contains("dev_pw")
                || effectiveDbPassword.equalsIgnoreCase("password")
                || effectiveDbPassword.equalsIgnoreCase("postgres")) {

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

        if (frontendUrl == null
                || frontendUrl.isBlank()
                || !frontendUrl.startsWith("https://")
                || frontendUrl.contains("localhost")
                || frontendUrl.contains("127.0.0.1")) {
            throw new IllegalStateException(
                    "Production requires a public APP_FRONTEND_URL without localhost");
        }

        if (origins.trim().equalsIgnoreCase("*") || origins.contains("http://")) {
            throw new IllegalStateException(
                    "Production CORS origins must use HTTPS and explicit hostnames");
        }

        if (otpRequired && !mailEnabled) {
            throw new IllegalStateException(
                    "Production email must be enabled when login OTP is required");
        }

        if (mailEnabled || otpRequired) {
            if (brevoApiKey == null || brevoApiKey.isBlank()) {
                throw new IllegalStateException(
                        "Production authentication/email requires BREVO_API_KEY");
            }
            if (brevoUrl == null || brevoUrl.isBlank() || !brevoUrl.startsWith("https://")) {
                throw new IllegalStateException(
                        "Production email requires a valid HTTPS BREVO_API_URL");
            }
            if (mailFrom == null
                    || mailFrom.isBlank()
                    || !mailFrom.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                    || mailFrom.contains("localhost")) {
                throw new IllegalStateException(
                        "Production email requires a valid Brevo sender email address");
            }

            // AAL authentication mail must always use the sender that is verified
            // in the Brevo account. This deliberately prevents a stale legacy
            // AAL_MAIL_FROM deployment variable from changing the sender.
            if (!"pmpumuropizzou@gmail.com".equalsIgnoreCase(mailFrom.trim())) {
                throw new IllegalStateException(
                        "Production Brevo sender must be pmpumuropizzou@gmail.com");
            }
        }
    }
}