package com.logiplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.regex.Pattern;

@Configuration
public class ProductionConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationValidator.class);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

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
            @Value("${app.mail.from:}") String mailFrom,
            @Value("${notifications.from-address:}") String notificationFrom,
            @Value("${notifications.smtp.enabled:false}") boolean smtpEnabled,
            @Value("${notifications.brevo.enabled:false}") boolean brevoNotificationsEnabled,
            @Value("${app.auth.otp.required:true}") boolean otpRequired,
            @Value("${app.mail.sender-domain:}") String senderDomain) {

        boolean production = "production".equalsIgnoreCase(environment == null ? "" : environment.trim());
        if (production) {
            validateJwt(jwtSecret);
            validateDatabase(dbUrl, dbUser, dbPassword);
            validateCors(origins);
            validateEnvironment(environment);
            validateFrontend(frontendUrl);

            if (smtpEnabled && brevoNotificationsEnabled) {
                throw new IllegalStateException(
                        "Production cannot enable both SMTP and Brevo operational notification adapters");
            }

            if (otpRequired && (!mailEnabled || brevoApiKey == null || brevoApiKey.isBlank())) {
                throw new IllegalStateException(
                        "Production OTP requires transactional email to be enabled and BREVO_API_KEY to be configured");
            }

            if (mailEnabled && brevoNotificationsEnabled) {
                validateHttpsProviderUrl(brevoUrl, "Brevo API URL");
                validateHttpsProviderUrl(brevoSendersUrl, "Brevo sender registry URL");
                validateSender(mailFrom, notificationFrom, senderDomain);
            }
        } else if (mailEnabled && brevoNotificationsEnabled && smtpEnabled) {
            throw new IllegalStateException("Do not enable SMTP and Brevo notification adapters at the same time");
        }

        boolean effectiveOtpRequired = otpRequired || production;
        if (effectiveOtpRequired && !mailEnabled) {
            log.warn(
                    "OTP delivery is required but transactional email is disabled; authentication will fail closed until email is configured");
        }
    }

    private void validateJwt(String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || containsPlaceholder(jwtSecret)) {
            throw new IllegalStateException("Production requires a strong JWT_SECRET of at least 32 bytes");
        }
    }

    private void validateDatabase(String dbUrl, String dbUser, String dbPassword) {
        if (dbUrl == null || dbUrl.isBlank() || dbUrl.contains("${")) {
            throw new IllegalStateException(
                    "Production requires SPRING_DATASOURCE_URL (or DATABASE_URL) to be configured");
        }
        boolean postgresJdbc = dbUrl.startsWith("jdbc:postgresql:");
        boolean postgresUri = dbUrl.startsWith("postgresql://") || dbUrl.startsWith("postgres://");
        if (!postgresJdbc && !postgresUri)
            throw new IllegalStateException("Production requires a PostgreSQL datasource URL");

        String effectiveDbUser = dbUser;
        String effectiveDbPassword = dbPassword;
        if (postgresUri) {
            try {
                String userInfo = URI.create(dbUrl).getUserInfo();
                if ((effectiveDbUser == null || effectiveDbUser.isBlank()) && userInfo != null) {
                    String[] credentials = userInfo.split(":", 2);
                    effectiveDbUser = credentials[0];
                    if ((effectiveDbPassword == null || effectiveDbPassword.isBlank()) && credentials.length == 2)
                        effectiveDbPassword = credentials[1];
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Production DATABASE_URL is not a valid PostgreSQL URI", ex);
            }
        }
        if (effectiveDbUser == null || effectiveDbUser.isBlank()
                || effectiveDbUser.equalsIgnoreCase("postgres") || effectiveDbUser.equalsIgnoreCase("logi")) {
            throw new IllegalStateException(
                    "Production must use a dedicated least-privilege application database role");
        }
        if (effectiveDbPassword == null || effectiveDbPassword.isBlank()
                || containsPlaceholder(effectiveDbPassword)
                || effectiveDbPassword.equalsIgnoreCase("password")
                || effectiveDbPassword.equalsIgnoreCase("postgres")) {
            throw new IllegalStateException("Production database password is not configured with a production secret");
        }
    }

    private void validateCors(String origins) {
        if (origins == null || origins.isBlank() || origins.contains("localhost")
                || origins.contains("*") || origins.contains("http://")) {
            throw new IllegalStateException("Production CORS must contain only explicit HTTPS trusted origins");
        }
    }

    private void validateEnvironment(String environment) {
        if (environment == null || !"production".equalsIgnoreCase(environment.trim())) {
            throw new IllegalStateException("Production profile requires app.environment=production");
        }
    }

    private void validateFrontend(String frontendUrl) {
        if (frontendUrl == null || frontendUrl.isBlank() || !frontendUrl.startsWith("https://")
                || frontendUrl.contains("localhost") || frontendUrl.contains("127.0.0.1")) {
            throw new IllegalStateException("Production requires a public APP_FRONTEND_URL without localhost");
        }
    }

    private void validateHttpsProviderUrl(String value, String label) {
        if (value == null || value.isBlank() || !value.startsWith("https://")) {
            throw new IllegalStateException(label + " must be configured with an HTTPS URL");
        }
    }

    private void validateSender(String mailFrom, String notificationFrom, String domain) {
        String sender = mailFrom != null && !mailFrom.isBlank() ? mailFrom.trim()
                : notificationFrom == null ? "" : notificationFrom.trim();
        if (sender.isBlank() || sender.indexOf('@') < 1) {
            throw new IllegalStateException("Production requires AAL_BREVO_SENDER_EMAIL / MAIL_FROM");
        }
        if (!EMAIL.matcher(sender).matches()) {
            throw new IllegalStateException("Production email sender must be a valid email address");
        }

        // The sender must be verified/active in Brevo. Do not impose a hard
        // AAL-domain restriction here: providers may legitimately verify an
        // external sender such as a Gmail address. The optional AAL_EMAIL_DOMAIN
        // setting is used only when resolving discovered senders automatically.
        if (domain != null && !domain.isBlank()) {
            log.info("Configured Brevo sender uses explicit provider-verified identity; sender-domain discovery filter remains enabled for fallback selection");
        }
    }

    private static boolean containsPlaceholder(String value) {
        String normalized = value == null ? "" : value.toLowerCase();
        return normalized.contains("dev-only") || normalized.contains("change-me")
                || normalized.contains("change-before-production");
    }
}
