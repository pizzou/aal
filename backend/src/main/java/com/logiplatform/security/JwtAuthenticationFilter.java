package com.logiplatform.security;

import com.logiplatform.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

        private static final String SESSION_COOKIE_NAME = "NLS_SESSION";
        private static final List<String> PUBLIC_ENDPOINT_PREFIXES = List.of(
                        "/api/auth/login",
                        "/api/auth/send-login-otp",
                        "/api/auth/password/forgot",
                        "/api/auth/password/reset",
                        "/api/auth/csrf",
                        "/api/public/",
                        "/actuator/health",
                        "/actuator/prometheus",
                        "/actuator/info");

        private final JwtService jwtService;
        private final JdbcTemplate authJdbcTemplate;
        private final UUID singleTenantId;

        public JwtAuthenticationFilter(
                        JwtService jwtService,
                        @Qualifier("authJdbcTemplate") JdbcTemplate authJdbcTemplate,
                        @Value("${app.single-tenant.id}") UUID singleTenantId) {

                this.jwtService = jwtService;
                this.authJdbcTemplate = authJdbcTemplate;
                this.singleTenantId = singleTenantId;
        }

        /**
         * Public endpoints intentionally ignore an optional JWT cookie.
         * An expired/corrupted session must never prevent a fresh login.
         */
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI();

                // Public endpoints must not be blocked by a stale/expired session
                // cookie. The authorization rules still decide whether the endpoint
                // itself is public; this only prevents a bad optional JWT from turning
                // a public request (especially login) into a 401.
                return PUBLIC_ENDPOINT_PREFIXES.stream()
                                .anyMatch(prefix ->
                                                prefix.endsWith("/")
                                                                ? path.startsWith(prefix)
                                                                : path.equals(prefix));
        }

        @Override
        protected void doFilterInternal(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain chain)
                        throws ServletException, IOException {

                String token = extractToken(request);

                /*
                 * No JWT is not an error here.
                 *
                 * Public endpoints and the login flow are intentionally allowed
                 * to continue without an authenticated SecurityContext.
                 */
                if (token == null || token.isBlank()) {
                        chain.doFilter(request, response);
                        return;
                }

                try {
                        Claims claims = jwtService.parseClaims(token);

                        String subject = claims.getSubject();

                        String tenantIdClaim = claims.get(
                                        "tenantId",
                                        String.class);

                        String role = claims.get(
                                        "role",
                                        String.class);

                        Number tokenVersionClaim = claims.get(
                                        "tokenVersion",
                                        Number.class);

                        if (subject == null || subject.isBlank()) {
                                throw new JwtException("JWT subject is missing");
                        }

                        if (tenantIdClaim == null || tenantIdClaim.isBlank()) {
                                throw new JwtException("JWT tenantId is missing");
                        }

                        if (role == null || role.isBlank()) {
                                throw new JwtException("JWT role is missing");
                        }

                        if (tokenVersionClaim == null) {
                                throw new JwtException("JWT tokenVersion is missing");
                        }

                        role = normalizeRole(role);

                        UUID userId = UUID.fromString(subject);
                        UUID tenantId = UUID.fromString(tenantIdClaim);

                        /*
                         * AAL is a single-tenant deployment.
                         */
                        if (!singleTenantId.equals(tenantId)) {
                                throw new JwtException(
                                                "Token belongs to an unauthorized tenant");
                        }

                        /*
                         * The JWT has already been cryptographically verified and the
                         * tenant claim has been checked against AAL's single tenant.
                         * Authentication must use the fixed single-tenant datasource here:
                         * this filter runs before Spring Security establishes the request
                         * authentication context and therefore must not depend on a
                         * tenant-aware datasource whose connection lifecycle is tied to
                         * TenantContext. The fixed datasource still applies the exact AAL
                         * tenant to PostgreSQL RLS.
                         */
                        TenantContext.setTenantId(tenantId);

                        UserSecurityState securityState = authJdbcTemplate.query(
                                        """
                                                        SELECT token_version, must_change_password
                                                        FROM users
                                                        WHERE id = ?
                                                          AND tenant_id = ?
                                                          AND active = true
                                                        """,
                                        rs -> rs.next()
                                                        ? new UserSecurityState(
                                                                        rs.getLong("token_version"),
                                                                        rs.getBoolean("must_change_password"))
                                                        : null,
                                        userId,
                                        tenantId);

                        if (securityState == null) {
                                throw new JwtException(
                                                "User is inactive, missing, or no longer belongs to tenant");
                        }

                        if (securityState.tokenVersion() != tokenVersionClaim.longValue()) {
                                throw new JwtException("Token revoked");
                        }

                        boolean mustChangePassword = securityState.mustChangePassword();
                        String path = request.getRequestURI();
                        boolean passwordSetupPath = path.equals("/api/auth/session") || path.equals("/api/auth/logout")
                                        || path.equals("/api/auth/change-password") || path.equals("/api/auth/csrf");
                        if (Boolean.TRUE.equals(mustChangePassword) && !passwordSetupPath) {
                                response.setStatus(428);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"error\":\"Password change required before continuing\"}");
                                return;
                        }

                        TenantPrincipal principal = new TenantPrincipal(
                                        userId,
                                        tenantId,
                                        role);

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                        principal,
                                        null,
                                        List.of(
                                                        new SimpleGrantedAuthority(
                                                                        "ROLE_" + role)));

                        SecurityContextHolder
                                        .getContext()
                                        .setAuthentication(authentication);

                        TenantContext.setTenantId(tenantId);

                        chain.doFilter(request, response);

                } catch (JwtException | IllegalArgumentException ex) {

                        SecurityContextHolder.clearContext();
                        TenantContext.clear();

                        // Browser sessions can retain an expired bearer token while the
                        // user is trying to authenticate again. Public authentication and
                        // public customer flows must not be blocked by that stale token.
                        if (isPublicWithoutBearer(request)) {
                                chain.doFilter(request, response);
                                return;
                        }

                        response.setStatus(
                                        HttpServletResponse.SC_UNAUTHORIZED);

                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.setHeader("Cache-Control", "no-store");
                        response.setHeader("Pragma", "no-cache");

                        response.getWriter().write(
                                        "{\"error\":\"Invalid or expired token\"}");

                } finally {

                        TenantContext.clear();
                        SecurityContextHolder.clearContext();
                }
        }


        private boolean isPublicWithoutBearer(HttpServletRequest request) {
                String path = request.getRequestURI();
                if (path == null) return false;

                return path.equals("/api/auth/login")
                                || path.equals("/api/auth/send-login-otp")
                                || path.equals("/api/auth/password/forgot")
                                || path.equals("/api/auth/password/reset")
                                || path.equals("/api/auth/csrf")
                                || path.startsWith("/api/public/");
        }

        private record UserSecurityState(long tokenVersion, boolean mustChangePassword) {}

        private String normalizeRole(String value) {
                String normalized = value == null
                                ? ""
                                : value.trim().toUpperCase(Locale.ROOT);

                while (normalized.startsWith("ROLE_")) {
                        normalized = normalized.substring("ROLE_".length());
                }

                if (normalized.isBlank()) {
                        throw new JwtException("JWT role is empty");
                }

                return normalized;
        }

        private String extractToken(HttpServletRequest request) {

                String authorization = request.getHeader("Authorization");

                if (authorization != null
                                && authorization.startsWith("Bearer ")) {

                        String bearerToken = authorization.substring(7).trim();

                        if (!bearerToken.isBlank()) {
                                return bearerToken;
                        }
                }

                return readCookie(
                                request,
                                SESSION_COOKIE_NAME);
        }

        private String readCookie(
                        HttpServletRequest request,
                        String name) {

                Cookie[] cookies = request.getCookies();

                if (cookies == null) {
                        return null;
                }

                for (Cookie cookie : cookies) {
                        if (name.equals(cookie.getName())) {
                                return cookie.getValue();
                        }
                }

                return null;
        }
}