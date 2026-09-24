package com.logiplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;

/**
 * Validates only invariants that make the application fundamentally unsafe or
 * impossible to start in production. Optional external integrations are
 * intentionally reported as readiness failures instead of crashing the
 * entire application context.
 */
@Configuration
@Profile("prod")
public class ProductionConfigurationValidator {

    private static final Logger log =
            LoggerFactory.getLogger(ProductionConfigurationValidator.class);

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
            @Value("${app.mail.brevo-senders-url:https://api.brevo.com/v3/senders}") String brevoSendersUrl,
            @Value("${app.auth.otp.required:true}") boolean otpRequired) {

        validateJwt(jwtSecret);
        validateDatabase(dbUrl, dbUser, dbPassword);
        validateCors(origins);
        validateEnvironment(environment);
        validateFrontend(frontendUrl);

        // Email/provider configuration is operational readiness, not application
        // construction. The authentication flow itself fails closed with a
        // SERVICE_UNAVAILABLE response when OTP delivery is unavailable.
        if (otpRequired && !mailEnabled) {
            log.warn(
                    "Production OTP is required but transactional email is disabled. "
                    + "Application will start, but login verification will remain unavailable "
                    + "until AAL_MAIL_ENABLED=true and a valid provider is configured.");
        }

        if (otpRequired || mailEnabled) {
            if (brevoApiKey == null || brevoApiKey.isBlank()) {
                log.warn(
                        "Transactional email provider API key is not configured. "
                        + "Configure BREVO_API_KEY before enabling production OTP.");
            }

            if (brevoUrl == null || brevoUrl.isBlank() || !brevoUrl.startsWith("https://")) {
                log.warn(
                        "Transactional email provider URL is invalid. "
                        + "Configure app.mail.brevo-url/BREVO_API_URL with an HTTPS URL.");
            }

            if (brevoSendersUrl == null
                    || brevoSendersUrl.isBlank()
                    || !brevoSendersUrl.startsWith("https://")) {
                log.warn(
                        "Brevo sender registry URL is invalid. "
                        + "Configure BREVO_SENDERS_API_URL with an HTTPS URL.");
            } else {
                log.info(
                        "Transactional email sender will be resolved from the explicit sender configuration "
                        + "or the active Brevo sender registry at runtime.");
            }
        }
    }

    private void validateJwt(String jwtSecret) {
        if (jwtSecret == null
                || jwtSecret.isBlank()
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || containsPlaceholder(jwtSecret)) {

            throw new IllegalStateException(
                    "Production requires a strong JWT_SECRET of at least 32 bytes");
        }
    }

    private void validateDatabase(
            String dbUrl,
            String dbUser,
            String dbPassword) {

        if (dbUrl == null || dbUrl.isBlank() || dbUrl.contains("${")) {
            throw new IllegalStateException(
                    "Production requires SPRING_DATASOURCE_URL (or DATABASE_URL) to be configured");
        }

        boolean postgresJdbc = dbUrl.startsWith("jdbc:postgresql:");
        boolean postgresUri =
                dbUrl.startsWith("postgresql://")
                        || dbUrl.startsWith("postgres://");

        if (!postgresJdbc && !postgresUri) {
            throw new IllegalStateException(
                    "Production requires a PostgreSQL datasource URL");
        }

        String effectiveDbUser = dbUser;
        String effectiveDbPassword = dbPassword;

        if (postgresUri) {
            try {
                String userInfo = URI.create(dbUrl).getUserInfo();

                if ((effectiveDbUser == null || effectiveDbUser.isBlank())
                        && userInfo != null) {

                    String[] credentials = userInfo.split(":", 2);
                    effectiveDbUser = credentials[0];

                    if ((effectiveDbPassword == null
                            || effectiveDbPassword.isBlank())
                            && credentials.length == 2) {

                        effectiveDbPassword = credentials[1];
                    }
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "Production DATABASE_URL is not a valid PostgreSQL URI",
                        ex);
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
    }

    private void validateCors(String origins) {
        if (origins == null
                || origins.isBlank()
                || origins.contains("localhost")
                || origins.contains("*")
                || origins.contains("http://")) {

            throw new IllegalStateException(
                    "Production CORS must contain only explicit HTTPS trusted origins");
        }
    }

    private void validateEnvironment(String environment) {
        if (environment == null
                || !"production".equalsIgnoreCase(environment.trim())) {

            throw new IllegalStateException(
                    "Production profile requires app.environment=production");
        }
    }

    private void validateFrontend(String frontendUrl) {
        if (frontendUrl == null
                || frontendUrl.isBlank()
                || !frontendUrl.startsWith("https://")
                || frontendUrl.contains("localhost")
                || frontendUrl.contains("127.0.0.1")) {

            throw new IllegalStateException(
                    "Production requires a public APP_FRONTEND_URL without localhost");
        }
    }

    private static boolean containsPlaceholder(String value) {
        return value.contains("dev-only")
                || value.contains("change-me")
                || value.contains("change-before-production");
    }
}
