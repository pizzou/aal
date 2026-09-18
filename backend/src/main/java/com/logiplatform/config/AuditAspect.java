
package com.logiplatform.config;

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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Objects;

/**
 * Records authenticated controller actions without persisting request bodies
 * or secrets.
 *
 * IMPORTANT:
 * Authentication/bootstrap/public controller endpoints execute before a
 * tenant is established. TenantContext.getTenantId() intentionally throws
 * when no tenant exists, so this aspect MUST check TenantContext.isSet()
 * before attempting to read the tenant ID.
 *
 * This prevents public endpoints such as:
 * GET /api/auth/csrf
 * POST /api/auth/login
 * GET /api/public/**
 *
 * from failing with HTTP 500 merely because they are not tenant-scoped.
 */
@Aspect
@Component
public class AuditAspect {

    private final AuditService audit;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    public AuditAspect(
            AuditService audit,
            HttpServletRequest request,
            HttpServletResponse response) {

        this.audit = audit;
        this.request = request;
        this.response = response;
    }

    @Around("""
            execution(* com.logiplatform.controller..*(..))
            && !within(com.logiplatform.controller.PublicTrackingController)
            """)
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {

        /*
         * Public/authentication/bootstrap requests do not have a tenant yet.
         *
         * TenantContext.getTenantId() deliberately throws when the context
         * is empty, therefore never call it unless isSet() is true.
         *
         * This is the critical fix for:
         * GET /api/auth/csrf -> HTTP 500
         */
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

        UUID resourceId = null;

        if (pjp.getArgs() != null) {
            resourceId = Arrays.stream(pjp.getArgs())
                    .filter(UUID.class::isInstance)
                    .map(UUID.class::cast)
                    .findFirst()
                    .orElse(null);
        }

        String resource = pjp.getSignature()
                .getDeclaringType()
                .getSimpleName()
                .replace("Controller", "");

        String action = pjp.getSignature()
                .getName();

        String method = request.getMethod();
        String path = request.getRequestURI();

        String ip = request.getHeader("X-Forwarded-For");

        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        } else {
            /*
             * X-Forwarded-For may contain a comma-separated proxy chain.
             * Record the originating address rather than the entire chain.
             */
            ip = ip.split(",")[0].trim();
        }

        String userAgent = request.getHeader("User-Agent");
        String correlation = request.getHeader("X-Correlation-ID");
        if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
        String before = Arrays.stream(pjp.getArgs()).filter(Objects::nonNull).map(Object::toString).collect(Collectors.joining(" | "));

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
                    true, before, result == null ? null : result.toString(), correlation);

            return result;

        } catch (Throwable ex) {

            int status = 500;

            if (ex instanceof ResponseStatusException responseStatusException) {
                status = responseStatusException
                        .getStatusCode()
                        .value();
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
                    false, before, null, correlation);

            throw ex;
        }
    }
}
