
package com.logiplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

        private static final int MAX_ATTEMPTS_PER_WINDOW = 10;
        private static final long WINDOW_SECONDS = 60L;

        private final StringRedisTemplate redisTemplate;
        private final DefaultRedisScript<Long> rateLimitScript;

        public AuthRateLimitFilter(
                        StringRedisTemplate redisTemplate) {

                this.redisTemplate = redisTemplate;

                this.rateLimitScript = new DefaultRedisScript<>();
                this.rateLimitScript.setLocation(
                                new ClassPathResource("rate_limit.lua"));
                this.rateLimitScript.setResultType(Long.class);
        }

        @Override
        protected void doFilterInternal(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain filterChain) throws ServletException, IOException {

                String uri = request.getRequestURI();

                /*
                 * CSRF bootstrap and session discovery are not login attempts.
                 * They must remain lightweight and must not consume the login bucket.
                 */
                if (isExcludedFromRateLimit(request)) {
                        filterChain.doFilter(request, response);
                        return;
                }

                if (!isRateLimited(uri)) {
                        filterChain.doFilter(request, response);
                        return;
                }

                String bucket = uri.startsWith("/api/auth/")
                                ? "auth"
                                : "public";

                String key = "ratelimit:"
                                + bucket
                                + ":"
                                + clientKey(request);

                Long count;

                try {
                        count = redisTemplate.execute(
                                        rateLimitScript,
                                        Collections.singletonList(key),
                                        String.valueOf(WINDOW_SECONDS));

                } catch (Exception exception) {

                        logger.error(
                                        "Redis rate limiter unavailable; rejecting protected request",
                                        exception);

                        writeServiceUnavailable(response);
                        return;
                }

                if (count != null
                                && count > MAX_ATTEMPTS_PER_WINDOW) {

                        writeRateLimited(response);
                        return;
                }

                filterChain.doFilter(request, response);
        }

        private boolean isExcludedFromRateLimit(
                        HttpServletRequest request) {

                String uri = request.getRequestURI();

                return "GET".equalsIgnoreCase(request.getMethod())
                                && ("/api/auth/csrf".equals(uri)
                                                || "/api/auth/session".equals(uri));
        }

        private boolean isRateLimited(String uri) {
                return uri.startsWith("/api/auth/")
                                || uri.startsWith("/api/public/");
        }

        private void writeServiceUnavailable(
                        HttpServletResponse response) throws IOException {

                response.setStatus(
                                HttpServletResponse.SC_SERVICE_UNAVAILABLE);

                response.setContentType(
                                "application/json");

                response.setCharacterEncoding("UTF-8");

                response.setHeader(
                                "Retry-After",
                                String.valueOf(WINDOW_SECONDS));

                response.getWriter().write(
                                "{\"error\":\"Security rate limiter temporarily unavailable\"}");
        }

        private void writeRateLimited(
                        HttpServletResponse response) throws IOException {

                response.setStatus(
                                HttpStatus.TOO_MANY_REQUESTS.value());

                response.setContentType(
                                "application/json");

                response.setCharacterEncoding("UTF-8");

                response.setHeader(
                                "Retry-After",
                                String.valueOf(WINDOW_SECONDS));

                response.getWriter().write(
                                "{\"error\":\"Too many attempts. Please wait and try again.\"}");
        }

        private String clientKey(
                        HttpServletRequest request) {

                String realIp = request.getHeader("X-Real-IP");
                if (isUsableIp(realIp)) {
                        return realIp.trim();
                }

                String forwardedFor = request.getHeader("X-Forwarded-For");
                if (forwardedFor != null && !forwardedFor.isBlank()) {
                        String first = forwardedFor.split(",", 2)[0].trim();
                        if (isUsableIp(first)) {
                                return first;
                        }
                }

                return request.getRemoteAddr();
        }

        private boolean isUsableIp(String value) {
                if (value == null || value.isBlank() || value.length() > 64) {
                        return false;
                }
                return value.matches("[0-9a-fA-F:.]+") || value.equalsIgnoreCase("unknown");
        }
}
