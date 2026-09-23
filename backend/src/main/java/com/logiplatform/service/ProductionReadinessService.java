package com.logiplatform.service;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductionReadinessService {

    private final DataSource dataSource;
    private final ObjectProvider<NotificationSenderPort> notificationSender;
    private final Environment environment;
    private final Flyway flyway;
    private final String appEnvironment;
    private final String frontendUrl;
    private final String jwtSecret;
    private final boolean otpRequired;
    private final boolean mailEnabled;
    private final String clamavHost;
    private final boolean clamavRequired;

    public ProductionReadinessService(
            DataSource dataSource,
            ObjectProvider<NotificationSenderPort> notificationSender,
            Environment environment,
            Flyway flyway,
            @Value("${app.environment:development}") String appEnvironment,
            @Value("${app.frontend.url:}") String frontendUrl,
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${app.auth.otp.required:false}") boolean otpRequired,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${document-security.clamav.host:}") String clamavHost,
            @Value("${document-security.clamav.required:false}") boolean clamavRequired) {
        this.dataSource = dataSource;
        this.notificationSender = notificationSender;
        this.environment = environment;
        this.flyway = flyway;
        this.appEnvironment = appEnvironment;
        this.frontendUrl = frontendUrl;
        this.jwtSecret = jwtSecret;
        this.otpRequired = otpRequired;
        this.mailEnabled = mailEnabled;
        this.clamavHost = clamavHost;
        this.clamavRequired = clamavRequired;
    }

    @PostConstruct
    void startupInvariantCheck() {
        if (otpRequired && notificationSender.getIfAvailable() == null) {
            throw new IllegalStateException("NotificationSenderPort must be available when OTP is required");
        }
    }

    public Map<String, Object> readiness(boolean strictProduction) {
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "database", databaseReady(), true, "PostgreSQL connection");
        check(checks, "flyway", flywayReady(), true, "Flyway schema state");
        check(checks, "notificationSender", notificationSender.getIfAvailable() != null, true,
                "Notification delivery adapter");
        check(checks, "jwtSecret", strongSecret(jwtSecret), strictProduction, "JWT signing secret");
        check(checks, "frontendUrl", httpsUrl(frontendUrl), strictProduction, "Public frontend URL");
        check(checks, "otpEmail", !otpRequired || mailEnabled, strictProduction && otpRequired,
                "OTP requires transactional email");
        check(checks, "clamav", !clamavRequired || (clamavHost != null && !clamavHost.isBlank()),
                strictProduction && clamavRequired, "Required malware-scanning service");

        boolean ready = checks.stream().allMatch(x -> Boolean.TRUE.equals(x.get("pass")) || !Boolean.TRUE.equals(x.get("blocking")));
        return Map.of(
                "ready", ready,
                "environment", appEnvironment,
                "timestamp", Instant.now().toString(),
                "profile", List.of(environment.getActiveProfiles()),
                "checks", checks);
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
            return flyway.info().current() != null && flyway.info().pending().length == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean strongSecret(String value) {
        return value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32
                && !value.contains("change-before-production")
                && !value.contains("change-me")
                && !value.contains("dev-only");
    }

    private static boolean httpsUrl(String value) {
        return value != null && value.startsWith("https://") && !value.contains("localhost");
    }

    private static void check(List<Map<String, Object>> checks, String key, boolean pass,
                              boolean blocking, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", key);
        row.put("pass", pass);
        row.put("blocking", blocking);
        row.put("detail", detail);
        checks.add(row);
    }
}
