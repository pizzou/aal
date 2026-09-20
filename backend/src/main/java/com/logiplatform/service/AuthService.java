package com.logiplatform.service;

import com.logiplatform.dto.AuthDtos.*;
import com.logiplatform.model.User;
import com.logiplatform.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthService.class);

    private static final int MAX_FAILURES = 5;
    private static final int LOCK_MINUTES = 15;
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final int OTP_MINUTES = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final MailService mail;
    private final UserNotificationService notifications;
    private final UUID tenantId;
    private final String frontendUrl;
    private final boolean otpRequired;

    public AuthService(
            @Qualifier("authJdbcTemplate") JdbcTemplate db,
            PasswordEncoder encoder,
            JwtService jwt,
            MailService mail,
            UserNotificationService notifications,
            @Value("${app.single-tenant.id}") UUID tenantId,
            @Value("${app.frontend.url:http://localhost:3000}") String frontendUrl,
            @Value("${app.auth.otp.required:false}") boolean otpRequired,
            @Value("${app.environment:development}") String environment
    ) {
        this.db = db;
        this.encoder = encoder;
        this.jwt = jwt;
        this.mail = mail;
        this.notifications = notifications;
        this.tenantId = tenantId;
        this.frontendUrl = frontendUrl;
        // Production authentication must always require the email OTP challenge.
        // A misconfigured Render/container environment must not silently disable 2FA.
        this.otpRequired = otpRequired || "production".equalsIgnoreCase(environment);
    }

    @Transactional(transactionManager = "authTransactionManager")
    public AuthResponse login(LoginRequest request) {

        String stage = "start";

        try {
            if (request == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Login request is required"
                );
            }

            if (request.email() == null || request.email().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Email is required"
                );
            }

            if (request.password() == null || request.password().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Password is required"
                );
            }

            stage = "normalize-email";

            String email = request.email()
                    .trim()
                    .toLowerCase(Locale.ROOT);

            log.info(
                    "Authentication started email={}",
                    maskEmail(email)
            );

            stage = "find-user";

            log.debug(
                    "Authentication stage={} tenantId={}",
                    stage,
                    tenantId
            );

            UserRecord u = find(email);

            log.debug(
                    "Authentication user loaded userId={} tenantId={} role={} active={} tokenVersion={}",
                    u.id(),
                    u.tenantId(),
                    u.role(),
                    u.active(),
                    u.tokenVersion()
            );

            Instant now = Instant.now();

            stage = "account-status";

            if (!u.active()) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Account is disabled"
                );
            }

            if (u.lockedUntil() != null
                    && u.lockedUntil().isAfter(now)) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Account temporarily locked. Try again later"
                );
            }

            stage = "password-check";

            log.debug(
                    "Authentication stage={} userId={}",
                    stage,
                    u.id()
            );

            if (!encoder.matches(request.password(), u.passwordHash())) {

                log.warn(
                        "Authentication failed: invalid credentials userId={} email={}",
                        u.id(),
                        maskEmail(email)
                );

                int failures = u.failedAttempts() + 1;

                if (failures >= MAX_FAILURES) {

                    stage = "lock-account";

                    db.update(
                            """
                            UPDATE users
                               SET failed_login_attempts=0,
                                   locked_until=?
                             WHERE id=?
                               AND tenant_id=?
                            """,
                            timestamp(
                                    now.plusSeconds(
                                            LOCK_MINUTES * 60L
                                    )
                            ),
                            u.id(),
                            tenantId
                    );

                } else {

                    stage = "record-login-failure";

                    db.update(
                            """
                            UPDATE users
                               SET failed_login_attempts=?
                             WHERE id=?
                               AND tenant_id=?
                            """,
                            failures,
                            u.id(),
                            tenantId
                    );
                }

                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"
                );
            }

            stage = "clear-login-failures";

            db.update(
                    """
                    UPDATE users
                       SET failed_login_attempts=0,
                           locked_until=NULL
                     WHERE id=?
                       AND tenant_id=?
                    """,
                    u.id(),
                    tenantId
            );

            if (!otpRequired) {
                stage = "generate-session-token";
                return success(u);
            }

            if (request.otp() == null || request.otp().isBlank()) {

                stage = "generate-otp-challenge";

                log.debug(
                        "Authentication stage={} userId={}",
                        stage,
                        u.id()
                );

                String challenge =
                        jwt.generateLoginOtpChallengeToken(
                                u.id(),
                                tenantId,
                                u.tokenVersion()
                        );

                stage = "issue-otp";

                log.debug(
                        "Authentication stage={} userId={}",
                        stage,
                        u.id()
                );

                issueOtpIfNeeded(u, now, false);

                log.info(
                        "Authentication requires OTP userId={} email={}",
                        u.id(),
                        maskEmail(email)
                );

                return new AuthResponse(
                        null,
                        tenantId.toString(),
                        u.id().toString(),
                        u.role(),
                        u.mustChangePassword(),
                        true,
                        challenge
                );
            }

            stage = "validate-otp-challenge";

            log.debug(
                    "Authentication stage={} userId={}",
                    stage,
                    u.id()
            );

            validateChallenge(
                    u,
                    request.otpChallengeToken()
            );

            stage = "verify-otp";

            log.debug(
                    "Authentication stage={} userId={}",
                    stage,
                    u.id()
            );

            verifyOtp(
                    u,
                    request.otp()
            );

            stage = "generate-session-token";

            log.debug(
                    "Authentication stage={} userId={}",
                    stage,
                    u.id()
            );

            return success(u);

        } catch (ResponseStatusException ex) {

            log.warn(
                    "Authentication rejected stage={} status={} reason={}",
                    stage,
                    ex.getStatusCode().value(),
                    ex.getReason()
            );

            throw ex;

        } catch (Exception ex) {

            log.error(
                    "AUTHENTICATION_FAILURE stage={} type={} message={}",
                    stage,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    ex
            );

            throw ex;
        }
    }

    @Transactional(transactionManager = "authTransactionManager")
    public void sendLoginOtp(String challenge) {

        if (!otpRequired) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Login verification is not enabled"
            );
        }

        String stage = "validate-login-otp-request";

        try {
            if (!jwt.isLoginOtpChallengeToken(challenge)) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid or expired verification request"
                );
            }

            stage = "read-challenge";

            UUID id = jwt.getUserId(challenge);
            UUID t = jwt.getTenantId(challenge);

            if (!tenantId.equals(t)) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid verification request"
                );
            }

            stage = "find-user";

            UserRecord u = findById(id);

            if (jwt.getTokenVersion(challenge) != u.tokenVersion()) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Verification request is no longer valid"
                );
            }

            stage = "issue-otp";

            issueOtpIfNeeded(u, Instant.now(), true);

        } catch (ResponseStatusException ex) {

            log.warn(
                    "Login OTP request rejected stage={} status={} reason={}",
                    stage,
                    ex.getStatusCode().value(),
                    ex.getReason()
            );

            throw ex;

        } catch (Exception ex) {

            log.error(
                    "LOGIN_OTP_FAILURE stage={} type={} message={}",
                    stage,
                    ex.getClass().getName(),
                    ex.getMessage(),
                    ex
            );

            throw ex;
        }
    }

    private void issueOtpIfNeeded(
            UserRecord u,
            Instant now,
            boolean forceNew
    ) {

        boolean active =
                u.otpHash() != null
                        && u.otpExpiresAt() != null
                        && u.otpExpiresAt().isAfter(now);

        if (active && !forceNew) {
            log.debug(
                    "Existing active OTP retained userId={} expiresAt={}",
                    u.id(),
                    u.otpExpiresAt()
            );
            return;
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expiresAt = now.plusSeconds(OTP_MINUTES * 60L);

        db.update(
                """
                UPDATE users
                   SET login_otp_hash=?,
                       login_otp_expires_at=?,
                       login_otp_attempts=0
                 WHERE id=?
                   AND tenant_id=?
                """,
                encoder.encode(code),
                timestamp(expiresAt),
                u.id(),
                tenantId
        );

        User mailUser = new User(tenantId, u.email(), "", u.role());
        mailUser.setDisplayName(u.displayName());

        try {
            mail.sendLoginOtp(mailUser, code, OTP_MINUTES);
        } catch (MailService.MailDeliveryException ex) {
            clearOtp(u.id());
            log.error(
                    "OTP email delivery failed userId={} reason={}",
                    u.id(),
                    ex.getMessage());
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to send the verification code. Please try again shortly.",
                    ex
            );
        }
    }

    private void validateChallenge(
            UserRecord u,
            String challenge
    ) {

        if (!jwt.isLoginOtpChallengeToken(challenge)
                || !u.id().equals(jwt.getUserId(challenge))
                || !tenantId.equals(jwt.getTenantId(challenge))
                || u.tokenVersion() != jwt.getTokenVersion(challenge)) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid or expired verification request"
            );
        }
    }

    private void verifyOtp(
            UserRecord u,
            String submitted
    ) {

        if (submitted == null
                || submitted.trim().isBlank()
                || !submitted.trim().matches("\\d{6}")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verification code must be 6 digits"
            );
        }

        UserRecord current =
                findById(u.id());

        Instant now =
                Instant.now();

        if (current.otpHash() == null
                || current.otpExpiresAt() == null
                || !current.otpExpiresAt().isAfter(now)) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Verification code expired. Request a new code."
            );
        }

        if (current.otpAttempts() >= MAX_OTP_ATTEMPTS) {

            clearOtp(current.id());

            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many incorrect codes. Request a new code."
            );
        }

        if (!encoder.matches(
                submitted.trim(),
                current.otpHash()
        )) {

            db.update(
                    """
                    UPDATE users
                       SET login_otp_attempts=login_otp_attempts+1
                     WHERE id=?
                       AND tenant_id=?
                    """,
                    current.id(),
                    tenantId
            );

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Incorrect verification code"
            );
        }

        int changed =
                db.update(
                        """
                        UPDATE users
                           SET login_otp_hash=NULL,
                               login_otp_expires_at=NULL,
                               login_otp_attempts=0
                         WHERE id=?
                           AND tenant_id=?
                           AND login_otp_hash=?
                           AND login_otp_expires_at>?
                        """,
                        current.id(),
                        tenantId,
                        current.otpHash(),
                        timestamp(now)
                );

        if (changed != 1) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Verification code has already been used"
            );
        }
    }

    private void clearOtp(UUID id) {

        db.update(
                """
                UPDATE users
                   SET login_otp_hash=NULL,
                       login_otp_expires_at=NULL,
                       login_otp_attempts=0
                 WHERE id=?
                   AND tenant_id=?
                """,
                id,
                tenantId
        );
    }

    private AuthResponse success(UserRecord u) {

        return new AuthResponse(
                jwt.generateToken(
                        u.id(),
                        tenantId,
                        u.role(),
                        u.tokenVersion()
                ),
                tenantId.toString(),
                u.id().toString(),
                u.role(),
                u.mustChangePassword(),
                false,
                null
        );
    }

    public boolean mustChangePassword(UUID id) {
        return findById(id).mustChangePassword();
    }

    @Transactional(transactionManager = "authTransactionManager")
    public void changeOwnPassword(
            UUID id,
            String currentPassword,
            String newPassword
    ) {

        UserRecord u =
                findById(id);

        if (!encoder.matches(
                currentPassword,
                u.passwordHash()
        )) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Current password is incorrect"
            );
        }

        validatePassword(newPassword);

        db.update(
                """
                UPDATE users
                   SET password_hash=?,
                       must_change_password=false,
                       token_version=token_version+1
                 WHERE id=?
                   AND tenant_id=?
                """,
                encoder.encode(newPassword),
                id,
                tenantId
        );
    }

    @Transactional(transactionManager = "authTransactionManager")
    public void logout(UUID id) {

        db.update(
                """
                UPDATE users
                   SET token_version=token_version+1,
                       login_otp_hash=NULL,
                       login_otp_expires_at=NULL
                 WHERE id=?
                   AND tenant_id=?
                """,
                id,
                tenantId
        );
    }

    @Transactional(transactionManager = "authTransactionManager")
    public void forgotPassword(String email) {

        UserRecord u;

        try {
            u = find(
                    email
                            .trim()
                            .toLowerCase(Locale.ROOT)
            );
        } catch (ResponseStatusException ex) {
            return;
        }

        String raw =
                UUID.randomUUID()
                        + ""
                        + UUID.randomUUID();

        String hash =
                sha256(raw);

        db.update(
                """
                UPDATE password_reset_tokens
                   SET used=true
                 WHERE user_id=?
                   AND tenant_id=?
                   AND used=false
                """,
                u.id(),
                tenantId
        );

        db.update(
                """
                INSERT INTO password_reset_tokens(
                    tenant_id,
                    user_id,
                    token_hash,
                    expires_at
                )
                VALUES(?,?,?,?)
                """,
                tenantId,
                u.id(),
                hash,
                timestamp(
                        Instant.now().plusSeconds(3600)
                )
        );

        User mailUser =
                new User(
                        tenantId,
                        u.email(),
                        "",
                        u.role()
                );

        mailUser.setDisplayName(
                u.displayName()
        );

        mail.sendPasswordReset(
                mailUser,
                raw,
                frontendUrl
        );
    }

    @Transactional(transactionManager = "authTransactionManager")
    public void resetPassword(
            String token,
            String newPassword
    ) {

        validatePassword(newPassword);

        String hash =
                sha256(token);

        java.util.Map<String, Object> row;

        try {

            row =
                    db.queryForMap(
                            """
                            SELECT user_id
                              FROM password_reset_tokens
                             WHERE token_hash=?
                               AND tenant_id=?
                               AND used=false
                               AND expires_at>now()
                            """,
                            hash,
                            tenantId
                    );

        } catch (Exception e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired reset link"
            );
        }

        UUID id =
                (UUID) row.get("user_id");

        db.update(
                """
                UPDATE users
                   SET password_hash=?,
                       must_change_password=false,
                       token_version=token_version+1,
                       login_otp_hash=NULL,
                       login_otp_expires_at=NULL
                 WHERE id=?
                   AND tenant_id=?
                """,
                encoder.encode(newPassword),
                id,
                tenantId
        );

        db.update(
                """
                UPDATE password_reset_tokens
                   SET used=true
                 WHERE token_hash=?
                   AND tenant_id=?
                """,
                hash,
                tenantId
        );
    }

    private UserRecord find(String email) {

        try {

            return db.queryForObject(
                    """
                    SELECT
                        id,
                        tenant_id,
                        password_hash,
                        role,
                        active,
                        failed_login_attempts,
                        locked_until,
                        token_version,
                        customer_client_id,
                        display_name,
                        phone,
                        must_change_password,
                        login_otp_hash,
                        login_otp_expires_at,
                        login_otp_attempts
                    FROM users
                    WHERE email=?
                      AND tenant_id=?
                    """,
                    (rs, n) ->
                            new UserRecord(
                                    UUID.fromString(
                                            rs.getString("id")
                                    ),
                                    UUID.fromString(
                                            rs.getString("tenant_id")
                                    ),
                                    email,
                                    rs.getString("password_hash"),
                                    rs.getString("role"),
                                    rs.getBoolean("active"),
                                    rs.getInt(
                                            "failed_login_attempts"
                                    ),
                                    rs.getTimestamp(
                                            "locked_until"
                                    ) == null
                                            ? null
                                            : rs.getTimestamp(
                                                    "locked_until"
                                            ).toInstant(),
                                    rs.getLong(
                                            "token_version"
                                    ),
                                    rs.getString(
                                            "customer_client_id"
                                    ),
                                    rs.getString(
                                            "display_name"
                                    ),
                                    rs.getString("phone"),
                                    rs.getBoolean(
                                            "must_change_password"
                                    ),
                                    rs.getString(
                                            "login_otp_hash"
                                    ),
                                    rs.getTimestamp(
                                            "login_otp_expires_at"
                                    ) == null
                                            ? null
                                            : rs.getTimestamp(
                                                    "login_otp_expires_at"
                                            ).toInstant(),
                                    rs.getInt(
                                            "login_otp_attempts"
                                    )
                            ),
                    email,
                    tenantId
            );

        } catch (EmptyResultDataAccessException e) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email or password"
            );
        }
    }

    private UserRecord findById(UUID id) {

        try {

            return db.queryForObject(
                    """
                    SELECT
                        id,
                        tenant_id,
                        email,
                        password_hash,
                        role,
                        active,
                        failed_login_attempts,
                        locked_until,
                        token_version,
                        customer_client_id,
                        display_name,
                        phone,
                        must_change_password,
                        login_otp_hash,
                        login_otp_expires_at,
                        login_otp_attempts
                    FROM users
                    WHERE id=?
                      AND tenant_id=?
                    """,
                    (rs, n) ->
                            new UserRecord(
                                    UUID.fromString(
                                            rs.getString("id")
                                    ),
                                    UUID.fromString(
                                            rs.getString("tenant_id")
                                    ),
                                    rs.getString("email"),
                                    rs.getString("password_hash"),
                                    rs.getString("role"),
                                    rs.getBoolean("active"),
                                    rs.getInt(
                                            "failed_login_attempts"
                                    ),
                                    rs.getTimestamp(
                                            "locked_until"
                                    ) == null
                                            ? null
                                            : rs.getTimestamp(
                                                    "locked_until"
                                            ).toInstant(),
                                    rs.getLong(
                                            "token_version"
                                    ),
                                    rs.getString(
                                            "customer_client_id"
                                    ),
                                    rs.getString(
                                            "display_name"
                                    ),
                                    rs.getString("phone"),
                                    rs.getBoolean(
                                            "must_change_password"
                                    ),
                                    rs.getString(
                                            "login_otp_hash"
                                    ),
                                    rs.getTimestamp(
                                            "login_otp_expires_at"
                                    ) == null
                                            ? null
                                            : rs.getTimestamp(
                                                    "login_otp_expires_at"
                                            ).toInstant(),
                                    rs.getInt(
                                            "login_otp_attempts"
                                    )
                            ),
                    id,
                    tenantId
            );

        } catch (EmptyResultDataAccessException e) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "User not found"
            );
        }
    }

    private void validatePassword(String p) {

        if (p == null
                || p.length() < 10
                || p.length() > 128
                || !p.matches(".*[A-Z].*")
                || !p.matches(".*[a-z].*")
                || !p.matches(".*\\d.*")
                || !p.matches(".*[^A-Za-z0-9].*")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Password must be 10-128 characters and include uppercase, lowercase, digit and special character"
            );
        }
    }

    private String sha256(String s) {

        try {

            byte[] b =
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(
                                    s.getBytes(
                                            StandardCharsets.UTF_8
                                    )
                            );

            StringBuilder x =
                    new StringBuilder();

            for (byte v : b) {
                x.append(
                        String.format(
                                "%02x",
                                v
                        )
                );
            }

            return x.toString();

        } catch (Exception e) {

            throw new IllegalStateException(e);
        }
    }

    private String maskEmail(String email) {

        if (email == null || email.isBlank()) {
            return "***";
        }

        int at =
                email.indexOf('@');

        if (at <= 1) {
            return "***";
        }

        String local =
                email.substring(
                        0,
                        at
                );

        String domain =
                email.substring(
                        at
                );

        String visible =
                local.substring(
                        0,
                        1
                );

        return visible
                + "***"
                + domain;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record UserRecord(
            UUID id,
            UUID tenantId,
            String email,
            String passwordHash,
            String role,
            boolean active,
            int failedAttempts,
            Instant lockedUntil,
            long tokenVersion,
            String customerClientId,
            String displayName,
            String phone,
            boolean mustChangePassword,
            String otpHash,
            Instant otpExpiresAt,
            int otpAttempts
    ) {
    }
}