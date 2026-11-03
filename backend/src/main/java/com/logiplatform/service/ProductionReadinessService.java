package com.logiplatform.service;

import com.logiplatform.integration.AirCargoProviderRegistry;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports deployment readiness without preventing the application from
 * starting.
 * Fatal infrastructure failures are handled by Spring/Flyway/DataSource itself;
 * configuration gaps are surfaced here as explicit readiness failures.
 */
@Service
public class ProductionReadinessService {

        private final DataSource dataSource;
        private final ObjectProvider<NotificationSenderPort> notificationSender;
        private final BrevoSenderResolver brevoSenderResolver;
        private final Environment environment;
        private final Flyway flyway;
        private final String appEnvironment;
        private final String frontendUrl;
        private final String jwtSecret;
        private final boolean otpRequired;
        private final boolean mailEnabled;
        private final String brevoApiKey;
        private final String brevoUrl;
        private final String clamavHost;
        private final int clamavPort;
        private final boolean clamavEnabled;
        private final String financialDocumentProvider;
        private final String supabaseUrl;
        private final String supabaseKey;
        private final String supabaseBucket;
        private final String backendUrl;
        private final boolean clamavRequired;
        private final String corsOrigins;
        private final String notificationProvider;
        private final boolean reconciliationEnabled;
        private final AirCargoProviderRegistry airCargoProviders;

        public ProductionReadinessService(
                        DataSource dataSource,
                        ObjectProvider<NotificationSenderPort> notificationSender,
                        BrevoSenderResolver brevoSenderResolver,
                        Environment environment,
                        Flyway flyway,
                        @Value("${app.environment:development}") String appEnvironment,
                        @Value("${app.frontend.url:}") String frontendUrl,
                        @Value("${jwt.secret:}") String jwtSecret,
                        @Value("${app.auth.otp.required:false}") boolean otpRequired,
                        @Value("${app.mail.enabled:false}") boolean mailEnabled,
                        @Value("${app.mail.brevo-api-key:}") String brevoApiKey,
                        @Value("${app.mail.brevo-url:https://api.brevo.com/v3/smtp/email}") String brevoUrl,
                        @Value("${document-security.clamav.host:}") String clamavHost,
                        @Value("${document-security.clamav.port:3310}") int clamavPort,
                        @Value("${document-security.clamav.enabled:false}") boolean clamavEnabled,
                        @Value("${financial-document.storage-provider:DATABASE}") String financialDocumentProvider,
                        @Value("${financial-document.supabase-url:}") String supabaseUrl,
                        @Value("${financial-document.supabase-service-key:}") String supabaseKey,
                        @Value("${financial-document.supabase-bucket:}") String supabaseBucket,
                        @Value("${app.backend.url:}") String backendUrl,
                        @Value("${document-security.clamav.required:false}") boolean clamavRequired,
                        @Value("${security.cors.allowed-origins:}") String corsOrigins,
                        @Value("${notifications.brevo.enabled:false}") boolean brevoNotificationsEnabled,
                        @Value("${notifications.smtp.enabled:false}") boolean smtpNotificationsEnabled,
                        @Value("${aircargo.reconciliation.enabled:false}") boolean reconciliationEnabled,
                        AirCargoProviderRegistry airCargoProviders) {

                this.dataSource = dataSource;
                this.notificationSender = notificationSender;
                this.brevoSenderResolver = brevoSenderResolver;
                this.environment = environment;
                this.flyway = flyway;
                this.appEnvironment = appEnvironment;
                this.frontendUrl = frontendUrl;
                this.jwtSecret = jwtSecret;
                this.otpRequired = otpRequired || "production".equalsIgnoreCase(appEnvironment);
                this.mailEnabled = mailEnabled;
                this.brevoApiKey = brevoApiKey;
                this.brevoUrl = brevoUrl;
                this.clamavHost = clamavHost;
                this.clamavPort = clamavPort;
                this.clamavEnabled = clamavEnabled;
                this.financialDocumentProvider = financialDocumentProvider;
                this.supabaseUrl = supabaseUrl;
                this.supabaseKey = supabaseKey;
                this.supabaseBucket = supabaseBucket;
                this.backendUrl = backendUrl;
                this.clamavRequired = clamavRequired;
                this.corsOrigins = corsOrigins;
                this.notificationProvider = brevoNotificationsEnabled ? "BREVO"
                                : smtpNotificationsEnabled ? "SMTP" : "LOGGING";
                this.reconciliationEnabled = reconciliationEnabled;
                this.airCargoProviders = airCargoProviders;
        }

        @PostConstruct
        void startupInvariantCheck() {
                if (notificationSender.getIfAvailable() == null) {
                        throw new IllegalStateException(
                                        "NotificationSenderPort must be available");
                }
        }

        public Map<String, Object> readiness(boolean strictProduction) {
                List<Map<String, Object>> checks = new ArrayList<>();

                check(
                                checks,
                                "database",
                                databaseReady(),
                                true,
                                "PostgreSQL connection");

                check(
                                checks,
                                "restrictedDatabaseUser",
                                restrictedDatabaseUser(),
                                strictProduction,
                                "Runtime PostgreSQL role must not have SUPERUSER, BYPASSRLS or public schema CREATE privileges");

                check(
                                checks,
                                "flyway",
                                flywayReady(),
                                true,
                                "Flyway schema state");

                check(
                                checks,
                                "notificationSender",
                                notificationSender.getIfAvailable() != null,
                                true,
                                "Notification delivery adapter");

                check(checks, "operationalEmailTransport",
                                !"LOGGING".equals(notificationProvider),
                                strictProduction,
                                "Operational notifications must use an enabled transactional provider, not a logging-only adapter");

                check(
                                checks,
                                "jwtSecret",
                                strongSecret(jwtSecret),
                                strictProduction,
                                "JWT signing secret");

                check(
                                checks,
                                "frontendUrl",
                                httpsUrl(frontendUrl),
                                strictProduction,
                                "Public frontend URL");

                boolean otpMailReady = !otpRequired
                                || (mailEnabled
                                                && !isBlank(brevoApiKey)
                                                && httpsUrl(brevoUrl)
                                                && brevoSenderResolver.isReady());

                check(
                                checks,
                                "otpEmail",
                                otpMailReady,
                                strictProduction && otpRequired,
                                "OTP requires enabled transactional email, a valid Brevo API key, HTTPS provider URL, and an active Brevo sender");

                // ClamAV must actually respond to the clamd PING protocol.
                // Hostname presence alone is not a security readiness check.
                boolean clamavReady = !clamavRequired
                                || (clamavEnabled && clamavResponds());

                check(
                                checks,
                                "clamav",
                                clamavReady,
                                strictProduction && clamavRequired,
                                "Required malware-scanning service");

                String archiveMode = financialDocumentProvider == null
                                ? "DATABASE"
                                : financialDocumentProvider.trim().toUpperCase(java.util.Locale.ROOT);
                boolean archiveReady = "DATABASE".equals(archiveMode)
                                || ("SUPABASE".equals(archiveMode)
                                                && httpsUrl(supabaseUrl)
                                                && !isBlank(supabaseKey)
                                                && !isBlank(supabaseBucket));
                check(checks, "financialDocumentArchive", archiveReady, strictProduction,
                                "Archive provider is DATABASE or configured private SUPABASE storage");
                check(checks, "financialDocumentPublicLinks", httpsUrl(backendUrl), strictProduction,
                                "Invoice and receipt links require the public HTTPS backend base URL");

                check(
                                checks,
                                "cors",
                                httpsCors(frontendUrl, corsOrigins),
                                strictProduction,
                                "Trusted production frontend origin is explicitly allowed by backend CORS");

                boolean liveBookingProviderConfigured = airCargoProviders.health().stream()
                                .anyMatch(p -> p.configured() && p.booking());
                boolean providerReady = !liveBookingProviderConfigured || reconciliationEnabled;
                check(
                                checks,
                                "airCargoReconciliation",
                                providerReady,
                                strictProduction && "production".equalsIgnoreCase(appEnvironment),
                                "Production airline operations require reconciliation enabled when live provider booking is used");

                boolean ready = checks.stream().allMatch(
                                x -> Boolean.TRUE.equals(x.get("pass"))
                                                || !Boolean.TRUE.equals(x.get("blocking")));

                return Map.of(
                                "ready", ready,
                                "environment", appEnvironment,
                                "timestamp", Instant.now().toString(),
                                "profile", List.of(environment.getActiveProfiles()),
                                "notificationProvider", notificationProvider,
                                "reconciliationEnabled", reconciliationEnabled,
                                "checks", checks);
        }

        private boolean clamavResponds() {
                if (isBlank(clamavHost) || clamavPort < 1 || clamavPort > 65535)
                        return false;
                try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(clamavHost, clamavPort), 1000);
                        socket.setSoTimeout(1000);
                        socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
                        byte[] response = socket.getInputStream().readNBytes(5);
                        return new String(response, StandardCharsets.US_ASCII).startsWith("PONG");
                } catch (Exception ignored) {
                        return false;
                }
        }

        private boolean restrictedDatabaseUser() {
                try (Connection c = dataSource.getConnection();
                                var stmt = c.createStatement();
                                var result = stmt.executeQuery("""
                                                SELECT NOT rolsuper AND NOT rolbypassrls
                                                       AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
                                                  FROM pg_roles WHERE rolname=current_user
                                                """)) {
                        return result.next() && result.getBoolean(1);
                } catch (Exception ignored) {
                        return false;
                }
        }

        private boolean databaseReady() {
                try (Connection c = dataSource.getConnection()) {
                        return c.isValid(2);
                } catch (Exception e) {
                        return false;
                }
        }

        private boolean flywayReady() {
                try {
                        return flyway.info().current() != null
                                        && flyway.info().pending().length == 0;
                } catch (Exception e) {
                        return false;
                }
        }

        private static boolean strongSecret(String value) {
                return value != null
                                && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32
                                && !value.contains("change-before-production")
                                && !value.contains("change-me")
                                && !value.contains("dev-only");
        }

        private static boolean httpsUrl(String value) {
                return value != null
                                && value.startsWith("https://")
                                && !value.contains("localhost");
        }

        private static boolean httpsCors(String frontend, String origins) {
                if (!httpsUrl(frontend) || origins == null || origins.isBlank() || origins.contains("*")
                                || origins.contains("http://"))
                        return false;
                for (String origin : origins.split(",")) {
                        if (origin.trim().equalsIgnoreCase(frontend.trim()))
                                return true;
                }
                return false;
        }

        private static boolean isBlank(String value) {
                return value == null || value.isBlank();
        }

        private static void check(
                        List<Map<String, Object>> checks,
                        String key,
                        boolean pass,
                        boolean blocking,
                        String detail) {

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", key);
                row.put("pass", pass);
                row.put("blocking", blocking);
                row.put("detail", detail);
                checks.add(row);
        }
}
