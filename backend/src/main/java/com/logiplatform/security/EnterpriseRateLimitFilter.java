package com.logiplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

/**
 * Application/API rate limiting. CORS preflight and long-lived SSE connections
 * are infrastructure traffic and are deliberately excluded from request buckets.
 * A Redis outage is fail-open here; Redis must not become a single point of
 * failure for the core logistics API.
 */
@Component
public class EnterpriseRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;
    private final int defaultLimit;
    private final int loginLimit;
    private final int bookingLimit;
    private final int webhookLimit;
    private final int uploadLimit;
    private final int searchLimit;
    private final int awbLimit;
    private final int adminLimit;

    public EnterpriseRateLimitFilter(
            StringRedisTemplate redis,
            @Value("${integration.rate-limit.default-per-minute:120}") int defaultLimit,
            @Value("${integration.rate-limit.login-per-minute:10}") int loginLimit,
            @Value("${integration.rate-limit.booking-per-minute:30}") int bookingLimit,
            @Value("${integration.rate-limit.webhook-per-minute:120}") int webhookLimit,
            @Value("${integration.rate-limit.upload-per-minute:30}") int uploadLimit,
            @Value("${integration.rate-limit.search-per-minute:60}") int searchLimit,
            @Value("${integration.rate-limit.awb-per-minute:30}") int awbLimit,
            @Value("${integration.rate-limit.admin-per-minute:60}") int adminLimit) {
        this.redis = redis;
        this.defaultLimit = defaultLimit;
        this.loginLimit = loginLimit;
        this.bookingLimit = bookingLimit;
        this.webhookLimit = webhookLimit;
        this.uploadLimit = uploadLimit;
        this.searchLimit = searchLimit;
        this.awbLimit = awbLimit;
        this.adminLimit = adminLimit;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (isInfrastructureRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        int limit = limit(request);
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }

        String bucket = category(request);
        String identity = identity(request);
        String key = "aal:rl:" + bucket + ":" + identity + ":" + (System.currentTimeMillis() / 60000);

        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(65));
            }
            if (count != null && count > limit) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        "{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests\"}");
                return;
            }
        } catch (Exception ignored) {
            // Redis outage must not turn the logistics API into an outage.
        }

        chain.doFilter(request, response);
    }

    private boolean isInfrastructureRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        return "OPTIONS".equalsIgnoreCase(method)
                || ("GET".equalsIgnoreCase(method) &&
                    (path.equals("/api/operations/events")
                        || path.startsWith("/actuator/")));
    }

    private int limit(HttpServletRequest request) {
        String p = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (p.equals("/api/auth/login") || p.equals("/api/auth/send-login-otp")) return loginLimit;
        if (p.contains("webhook")) return webhookLimit;
        if (p.contains("/air-cargo/bookings")) return bookingLimit;
        if (p.contains("/awb")) return awbLimit;
        if (p.contains("upload") || ("POST".equalsIgnoreCase(request.getMethod()) && p.contains("documents"))) return uploadLimit;
        if (p.contains("search")) return searchLimit;
        if (p.startsWith("/api/users") || p.startsWith("/api/settings") || p.startsWith("/api/platform")) return adminLimit;
        return defaultLimit;
    }

    private String category(HttpServletRequest request) {
        return request.getRequestURI().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return authentication.getName();
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",", 2)[0].trim();
        return request.getRemoteAddr();
    }
}
