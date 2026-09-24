package com.logiplatform.controller;

import com.logiplatform.dto.AuthDtos.*;
import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_COOKIE = "NLS_SESSION";
    private static final String CSRF_COOKIE = "NLS_CSRF";

    private final SecureRandom random = new SecureRandom();
    private final AuthService authService;

    @Value("${app.environment:development}")
    private String environment;

    @Value("${app.auth.cookie.same-site:None}")
    private String cookieSameSite;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request) {

        AuthResponse response = authService.login(request);

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.VARY, "Origin");

        if (response.accessToken() == null) {
            return builder.body(response);
        }

        ResponseCookie session = sessionCookie(response.accessToken());
        return builder
                .header(HttpHeaders.SET_COOKIE, session.toString())
                .body(response);
    }

    @PatchMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @RequestBody ChangePasswordRequest request,
            Authentication authentication) {

        if (authentication == null
                || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            return ResponseEntity.status(401).build();
        }

        authService.changeOwnPassword(
                principal.userId(),
                request.currentPassword(),
                request.newPassword());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/send-login-otp")
    public ResponseEntity<Void> sendLoginOtp(
            @Valid @RequestBody LoginOtpRequest request) {

        authService.sendLoginOtp(request.otpChallengeToken());

        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(
            @CookieValue(name = CSRF_COOKIE, required = false) String existing) {

        String token = existing != null
                && existing.length() == 64
                && isHex(existing)
                        ? existing
                        : newCsrf();

        ResponseCookie cookie = ResponseCookie.from(CSRF_COOKIE, token)
                .httpOnly(false)
                .secure(cookieSecure())
                .sameSite(normalizedSameSite())
                .path("/")
                .maxAge(Duration.ofHours(8))
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new CsrfResponse(token));
    }

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> session(Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                    .body(SessionResponse.anonymous());
        }

        boolean mustChange = authService.mustChangePassword(principal.userId());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .body(new SessionResponse(
                        true,
                        principal.tenantId().toString(),
                        principal.userId().toString(),
                        principal.role(),
                        mustChange));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {

        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof TenantPrincipal principal) {
            authService.logout(principal.userId());
        }

        ResponseCookie session = ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure())
                .sameSite(normalizedSameSite())
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        ResponseCookie csrf = ResponseCookie.from(CSRF_COOKIE, "")
                .httpOnly(false)
                .secure(cookieSecure())
                .sameSite(normalizedSameSite())
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE, session.toString())
                .header(HttpHeaders.SET_COOKIE, csrf.toString())
                .build();
    }

    private ResponseCookie sessionCookie(String accessToken) {
        return ResponseCookie.from(SESSION_COOKIE, accessToken)
                .httpOnly(true)
                .secure(cookieSecure())
                .sameSite(normalizedSameSite())
                .path("/")
                .maxAge(Duration.ofHours(1))
                .build();
    }

    private boolean isProduction() {
        return "production".equalsIgnoreCase(environment);
    }

    private String normalizedSameSite() {
        String value = cookieSameSite == null ? "None" : cookieSameSite.trim();
        if ("strict".equalsIgnoreCase(value))
            return "Strict";
        if ("lax".equalsIgnoreCase(value))
            return "Lax";
        return "None";
    }

    private boolean cookieSecure() {
        return isProduction() || "None".equalsIgnoreCase(normalizedSameSite());
    }

    private String newCsrf() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private boolean isHex(String value) {
        for (char character : value.toCharArray()) {
            if (!Character.toString(character).matches("[0-9a-fA-F]")) {
                return false;
            }
        }
        return true;
    }

    public record SessionResponse(
            boolean authenticated,
            String tenantId,
            String userId,
            String role,
            boolean mustChangePassword) {

        public static SessionResponse anonymous() {
            return new SessionResponse(false, null, null, null, false);
        }
    }

    public record CsrfResponse(String token) {
    }

    public record ChangePasswordRequest(
            String currentPassword,
            String newPassword) {
    }
}
