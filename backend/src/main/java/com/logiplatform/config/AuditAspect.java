package com.logiplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.service.AuditService;
import com.logiplatform.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Records authenticated controller mutations without storing credentials,
 * bearer tokens or other authentication secrets in the audit state columns.
 */
@Aspect
@Component
public class AuditAspect {

    private static final int MAX_STATE_LENGTH = 12000;
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password",
            "currentpassword",
            "newpassword",
            "temporarypassword",
            "token",
            "accesstoken",
            "refreshtoken",
            "otp",
            "otpcode",
            "otpchallengetoken",
            "secret",
            "apikey",
            "brevoapikey",
            "authorization",
            "cookie",
            "csrf",
            "csrftoken"
    );

    private final AuditService audit;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final ObjectMapper objectMapper;

    public AuditAspect(
            AuditService audit,
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper objectMapper) {
        this.audit = audit;
        this.request = request;
        this.response = response;
        this.objectMapper = objectMapper;
    }

    @Around("""
            execution(* com.logiplatform.controller..*(..))
            && !within(com.logiplatform.controller.PublicTrackingController)
            """)
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        if (!TenantContext.isSet()) {
            return pjp.proceed();
        }

        UUID tenantId = TenantContext.getTenantId();
        UUID userId = null;

        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof TenantPrincipal principal) {
            userId = principal.userId();
        }

        UUID resourceId = Arrays.stream(pjp.getArgs())
                .filter(UUID.class::isInstance)
                .map(UUID.class::cast)
                .findFirst()
                .orElse(null);

        String resource = pjp.getSignature()
                .getDeclaringType()
                .getSimpleName()
                .replace("Controller", "");

        String action = pjp.getSignature().getName();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }

        String userAgent = request.getHeader("User-Agent");
        String correlation = request.getHeader("X-Correlation-ID");
        if (correlation == null || correlation.isBlank()) {
            correlation = UUID.randomUUID().toString();
        }

        boolean mutation = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);

        String before = mutation ? snapshot(pjp.getArgs()) : null;

        try {
            Object result = pjp.proceed();

            audit.record(
                    tenantId,
                    userId,
                    action,
                    resource,
                    resourceId,
                    method,
                    path,
                    ip,
                    userAgent,
                    response.getStatus(),
                    true,
                    before,
                    mutation ? snapshot(result) : null,
                    correlation);

            return result;
        } catch (Throwable ex) {
            int status = 500;

            if (ex instanceof ResponseStatusException responseStatusException) {
                status = responseStatusException.getStatusCode().value();
            }

            audit.record(
                    tenantId,
                    userId,
                    action,
                    resource,
                    resourceId,
                    method,
                    path,
                    ip,
                    userAgent,
                    status,
                    false,
                    before,
                    null,
                    correlation);

            throw ex;
        }
    }

    private String snapshot(Object value) {
        if (value == null) return null;

        try {
            JsonNode node = objectMapper.valueToTree(value);
            JsonNode sanitized = redact(node);
            String json = objectMapper.writeValueAsString(sanitized);
            return json.length() <= MAX_STATE_LENGTH
                    ? json
                    : json.substring(0, MAX_STATE_LENGTH) + "…";
        } catch (Exception ex) {
            return "[audit-state-unavailable:" + value.getClass().getSimpleName() + "]";
        }
    }

    private JsonNode redact(JsonNode node) {
        if (node == null) return null;

        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (SENSITIVE_FIELD_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                    object.set(name, objectMapper.getNodeFactory().textNode("[REDACTED]"));
                } else {
                    redact(object.get(name));
                }
            }
            return object;
        }

        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, redact(array.get(i)));
            }
        }

        return node;
    }
}
