package com.logiplatform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-minutes:60}") long expirationMinutes) {

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }

        if (expirationMinutes < 5 || expirationMinutes > 1440) {
            throw new IllegalStateException(
                    "jwt.expiration-minutes must be between 5 and 1440"
            );
        }

        this.key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        this.expirationMinutes = expirationMinutes;
    }

    public String generateToken(
            UUID userId,
            UUID tenantId,
            String role) {

        return generateToken(
                userId,
                tenantId,
                role,
                0L
        );
    }

    public String generateToken(
            UUID userId,
            UUID tenantId,
            String role,
            long tokenVersion) {

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", normalizeRole(role))
                .claim("tokenVersion", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(
                        Date.from(
                                now.plusSeconds(
                                        expirationMinutes * 60
                                )
                        )
                )
                .signWith(key)
                .compact();
    }

    private String normalizeRole(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);

        while (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring("ROLE_".length());
        }

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Role is required");
        }

        return normalized;
    }

    public String generateLoginOtpChallengeToken(
            UUID userId,
            UUID tenantId,
            long tokenVersion) {

        Instant now = Instant.now();

        return Jwts.builder()
                .subject(userId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("tokenVersion", tokenVersion)
                .claim("purpose", "LOGIN_OTP")
                .issuedAt(Date.from(now))
                .expiration(
                        Date.from(
                                now.plusSeconds(5 * 60)
                        )
                )
                .signWith(key)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isLoginOtpChallengeToken(String token) {
        try {
            return "LOGIN_OTP".equals(
                    parseClaims(token)
                            .get("purpose", String.class)
            );
        } catch (Exception e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(
                parseClaims(token).getSubject()
        );
    }

    public UUID getTenantId(String token) {
        return UUID.fromString(
                parseClaims(token)
                        .get("tenantId", String.class)
        );
    }

    public long getTokenVersion(String token) {
        Number value = parseClaims(token)
                .get("tokenVersion", Number.class);

        return value == null
                ? 0L
                : value.longValue();
    }
}