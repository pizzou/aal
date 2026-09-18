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
        private static final String CSRF_ENDPOINT = "/api/auth/csrf";

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
         * The CSRF token endpoint is intentionally excluded from JWT
         * authentication.
         *
         * The browser must be able to obtain a CSRF token before it has
         * authenticated. More importantly, an expired or corrupted
         * NLS_SESSION cookie must never prevent CSRF token generation.
         */
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
                return CSRF_ENDPOINT.equals(request.getRequestURI());
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

                        role = role.trim().toUpperCase(Locale.ROOT);

                        UUID userId = UUID.fromString(subject);
                        UUID tenantId = UUID.fromString(tenantIdClaim);

                        /*
                         * AAL is a single-tenant deployment.
                         */
                        if (!singleTenantId.equals(tenantId)) {
                                throw new JwtException(
                                                "Token belongs to an unauthorized tenant");
                        }

                        Long currentTokenVersion = authJdbcTemplate.query(
                                        """
                                                        SELECT token_version
                                                        FROM users
                                                        WHERE id = ?
                                                          AND tenant_id = ?
                                                          AND active = true
                                                        """,
                                        rs -> rs.next()
                                                        ? rs.getLong(1)
                                                        : null,
                                        userId,
                                        tenantId);

                        if (currentTokenVersion == null) {
                                throw new JwtException(
                                                "User is inactive, missing, or no longer belongs to tenant");
                        }

                        if (currentTokenVersion.longValue() != tokenVersionClaim.longValue()) {
                                throw new JwtException("Token revoked");
                        }

                        Boolean mustChangePassword = authJdbcTemplate.query(
                                        "SELECT must_change_password FROM users WHERE id=? AND tenant_id=? AND active=true",
                                        rs -> rs.next() ? rs.getBoolean(1) : false, userId, tenantId);
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

                        response.setStatus(
                                        HttpServletResponse.SC_UNAUTHORIZED);

                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");

                        response.getWriter().write(
                                        "{\"error\":\"Invalid or expired token\"}");

                } finally {

                        TenantContext.clear();
                        SecurityContextHolder.clearContext();
                }
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