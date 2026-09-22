package com.logiplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class BrowserCsrfFilter extends OncePerRequestFilter {
    private static final String SESSION = "NLS_SESSION";
    private static final String CSRF_COOKIE = "NLS_CSRF";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final List<String> PUBLIC_MUTATION_PREFIXES = List.of(
            "/api/auth/login",
            "/api/auth/send-login-otp",
            "/api/auth/password/forgot",
            "/api/auth/password/reset",
            "/api/public/commercial/",
            "/api/public/quotes/",
            "/api/public/tracking/");

    private static final java.util.Set<String> MUTATING = java.util.Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PUBLIC_MUTATION_PREFIXES.stream().anyMatch(prefix ->
                prefix.endsWith("/") ? path.startsWith(prefix) : path.equals(prefix));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (MUTATING.contains(req.getMethod()) && hasCookie(req, SESSION) && !csrfValid(req)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.setHeader("Cache-Control", "no-store");
            res.getWriter().write("{\"error\":\"CSRF validation failed\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean csrfValid(HttpServletRequest req) {
        String cookie = null;
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (CSRF_COOKIE.equals(c.getName())) {
                    cookie = c.getValue();
                    break;
                }
            }
        }
        String header = req.getHeader(CSRF_HEADER);
        if (cookie == null || header == null) return false;
        return MessageDigest.isEqual(
                cookie.getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return false;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return true;
        return false;
    }
}
