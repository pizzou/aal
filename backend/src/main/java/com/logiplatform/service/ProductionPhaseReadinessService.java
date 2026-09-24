package com.logiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductionPhaseReadinessService {
    private final ProductionReadinessService base;
    private final String environment;
    private final boolean otpRequired;
    private final boolean mailEnabled;
    private final boolean clamavRequired;
    private final String frontendUrl;

    public ProductionPhaseReadinessService(
            ProductionReadinessService base,
            @Value("${app.environment:development}") String environment,
            @Value("${app.auth.otp.required:false}") boolean otpRequired,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${document-security.clamav.required:false}") boolean clamavRequired,
            @Value("${app.frontend.url:}") String frontendUrl) {
        this.base = base;
        this.environment = environment;
        this.otpRequired = otpRequired || "production".equalsIgnoreCase(environment);
        this.mailEnabled = mailEnabled;
        this.clamavRequired = clamavRequired;
        this.frontendUrl = frontendUrl;
    }

    public Map<String, Object> report() {
        Map<String, Object> infrastructure = base.readiness("production".equalsIgnoreCase(environment));
        List<Map<String, Object>> phases = new ArrayList<>();
        add(phases, 1, "Production foundation", Boolean.TRUE.equals(infrastructure.get("ready")), "Database/Flyway/readiness checks");
        add(phases, 2, "Authentication and session security", !otpRequired || mailEnabled, "Production OTP requires transactional email");
        add(phases, 3, "API reliability", true, "Central errors, correlation IDs and explicit authorization matchers");
        add(phases, 4, "Core TMS", true, "Shipment/transport/operations modules require E2E sign-off");
        add(phases, 5, "Commercial and rating", true, "Quote/rating/billing modules require regression sign-off");
        add(phases, 6, "Multimodal execution", true, "Air/ocean/road/rail modules require integration sign-off");
        add(phases, 7, "Fleet/WMS/IoT", true, "Fleet GPS is batch-optimized; live device feeds require sign-off");
        add(phases, 8, "Customs/DG/documents", !clamavRequired || Boolean.TRUE.equals(infrastructure.get("ready")), "Required malware scanning must pass when enabled");
        add(phases, 9, "Finance and reconciliation", true, "Finance/reconciliation requires accounting UAT");
        add(phases, 10, "Notifications and integrations", !otpRequired || mailEnabled, "Transactional notifications must be enabled");
        add(phases, 11, "Testing", true, "Backend integration and frontend E2E suites are release gates");
        add(phases, 12, "AWS deployment", true, "Use TLS, managed DB, secrets, backups, monitoring and rollback");
        add(phases, 13, "Final production gate", Boolean.TRUE.equals(infrastructure.get("ready")), "All blocking infrastructure checks must pass");
        boolean ready = phases.stream().allMatch(p -> Boolean.TRUE.equals(p.get("pass")));
        return Map.of("ready", ready, "environment", environment, "frontendUrl", frontendUrl, "timestamp", Instant.now().toString(), "infrastructure", infrastructure, "phases", phases);
    }

    private static void add(List<Map<String, Object>> phases, int number, String name, boolean pass, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", number);
        row.put("name", name);
        row.put("pass", pass);
        row.put("detail", detail);
        phases.add(row);
    }
}
