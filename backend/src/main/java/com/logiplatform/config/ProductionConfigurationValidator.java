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
            @Value("${app.environment}") String environment,
            @Value("${app.frontend.url}") String frontendUrl,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${app.mail.brevo-api-key:}") String brevoApiKey,
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
                || dbPassword.equalsIgnoreCase("password")
                || dbPassword.equalsIgnoreCase("postgres")) {

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

        if (mailEnabled || otpRequired) {
            if (brevoApiKey == null || brevoApiKey.isBlank()) {
                throw new IllegalStateException(
                        "Production authentication/email requires BREVO_API_KEY");
            }
            if (mailFrom == null || mailFrom.isBlank() || !mailFrom.contains("@") || mailFrom.contains("localhost")) {
                throw new IllegalStateException(
                        "Production email requires a valid AAL_MAIL_FROM address");
            }
        }
    }
}