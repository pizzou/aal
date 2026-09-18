package com.logiplatform.config;

import com.logiplatform.model.RoleName;
import com.logiplatform.model.User;
import com.logiplatform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.UUID;

@Configuration
public class AdminCredentialSeeder {

    private static final Logger log =
            LoggerFactory.getLogger(AdminCredentialSeeder.class);

    @Value("${app.admin-seed.enabled:false}")
    private boolean enabled;

    @Value("${app.admin-seed.email:}")
    private String configuredEmail;

    @Value("${app.admin-seed.password:}")
    private String configuredPassword;

    @Value("${app.admin-seed.display-name:AAL Administrator}")
    private String configuredDisplayName;

    @Value("${app.single-tenant.id}")
    private UUID tenantId;

    @Bean
    ApplicationRunner seedAdminCredential(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        return args -> {

            if (!enabled) {
                log.debug(
                        "Admin credential seeding is disabled"
                );
                return;
            }

            String email =
                    configuredEmail == null
                            ? ""
                            : configuredEmail.trim()
                                    .toLowerCase(Locale.ROOT);

            String password =
                    configuredPassword == null
                            ? ""
                            : configuredPassword;

            String displayName =
                    configuredDisplayName == null
                            || configuredDisplayName.isBlank()
                            ? "AAL Administrator"
                            : configuredDisplayName.trim();

            if (email.isBlank()) {
                throw new IllegalStateException(
                        "AAL admin seeding is enabled but AAL_ADMIN_EMAIL is empty"
                );
            }

            if (password.isBlank()) {
                throw new IllegalStateException(
                        "AAL admin seeding is enabled but AAL_ADMIN_PASSWORD is empty"
                );
            }

            if (password.length() < 12) {
                throw new IllegalStateException(
                        "AAL admin password must contain at least 12 characters"
                );
            }

            if (userRepository.existsByEmail(email)) {
                log.info(
                        "Admin seed skipped because user already exists: {}",
                        maskEmail(email)
                );
                return;
            }

            User admin =
                    new User(
                            tenantId,
                            email,
                            passwordEncoder.encode(password),
                            RoleName.ADMIN.name()
                    );

            admin.setDisplayName(displayName);
            admin.setActive(true);
            admin.setFailedLoginAttempts(0);
            admin.setLockedUntil(null);
            admin.setTokenVersion(0L);
            admin.setCustomerClientId(null);
            admin.setMustChangePassword(false);
            admin.setLoginOtpHash(null);
            admin.setLoginOtpExpiresAt(null);
            admin.setLoginOtpAttempts(0);

            User saved =
                    userRepository.save(admin);

            log.info(
                    "AAL administrator seeded successfully userId={} email={}",
                    saved.getId(),
                    maskEmail(email)
            );
        };
    }

    private String maskEmail(String email) {

        int at =
                email.indexOf('@');

        if (at <= 1) {
            return "***";
        }

        return email.charAt(0)
                + "***"
                + email.substring(at);
    }
}