
package com.logiplatform.service;

import com.logiplatform.model.Role;
import com.logiplatform.model.RoleName;
import com.logiplatform.model.User;
import com.logiplatform.repository.UserRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserService {

    private static final String ADMIN_ROLE = RoleName.ADMIN.name();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final MailService mailService;
    private final String frontendUrl;
    private final UserNotificationService userNotifications;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate jdbc,
            MailService mailService,
            UserNotificationService userNotifications,
            @org.springframework.beans.factory.annotation.Value("${app.frontend.url:http://localhost:3000}") String frontendUrl) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.mailService = mailService;
        this.frontendUrl = frontendUrl;
        this.userNotifications = userNotifications;
    }

    /**
     * Creates a user inside the current AAL tenant.
     *
     * The tenant is NEVER accepted from the browser.
     * It always comes from TenantContext established by JWT authentication.
     */
    @Transactional
    public User createUser(String email, String rawPassword, RoleName roleName) {
        return createUser(email, null, null, roleName);
    }

    @Transactional
    public User createUser(String email, String displayName, String phone, RoleName roleName) {
        UUID tenantId = currentTenant();
        String normalizedEmail = normalizeEmail(email);
        if (roleName == null) throw new IllegalArgumentException("Role is required");
        if (userRepository.existsByEmail(normalizedEmail)) throw new IllegalArgumentException("Email already exists: " + normalizedEmail);

        String temporaryPassword = generateTemporaryPassword();
        User user = new User(tenantId, normalizedEmail, passwordEncoder.encode(temporaryPassword), roleName.name());
        user.setDisplayName(displayName == null || displayName.isBlank() ? normalizedEmail : displayName.trim());
        user.setPhone(phone == null ? null : phone.trim());
        user.setActive(true);
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setTokenVersion(0L);
        try {
            User saved = userRepository.save(user);
            mailService.sendNewUserCredentials(saved, temporaryPassword, frontendUrl + "/login");
            userNotifications.notifyUser(saved.getId(), "ACCOUNT", "AAL account created", "Your AAL account has been created. Check your email for the temporary password.", "/login");
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("Email already exists: " + normalizedEmail);
        }
    }

    private String generateTemporaryPassword() {
        final String upper="ABCDEFGHJKLMNPQRSTUVWXYZ", lower="abcdefghijkmnpqrstuvwxyz", digits="23456789", special="!@#$%&*?";
        final String all=upper+lower+digits+special;
        java.security.SecureRandom r=new java.security.SecureRandom();
        StringBuilder b=new StringBuilder(); b.append(upper.charAt(r.nextInt(upper.length()))); b.append(lower.charAt(r.nextInt(lower.length()))); b.append(digits.charAt(r.nextInt(digits.length()))); b.append(special.charAt(r.nextInt(special.length())));
        while(b.length()<12)b.append(all.charAt(r.nextInt(all.length())));
        char[] a=b.toString().toCharArray(); for(int i=a.length-1;i>0;i--){int j=r.nextInt(i+1);char t=a[i];a[i]=a[j];a[j]=t;} return new String(a);
    }

    @Transactional
    public User linkCustomer(UUID id, String clientId) {
        validateId(id);
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("Client ID is required");
        User user = getById(id);
        if (!RoleName.CUSTOMER.name().equals(user.getRole())) throw new IllegalArgumentException("User must have CUSTOMER role");
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM client_records WHERE tenant_id=? AND client_id=?",Integer.class,TenantContext.getTenantId(),clientId.trim());
        if (exists == null || exists == 0) throw new IllegalArgumentException("Client record not found: " + clientId);
        jdbc.update("UPDATE users SET customer_client_id=?, token_version=token_version+1 WHERE id=? AND tenant_id=?",clientId.trim(),id,TenantContext.getTenantId());
        user.setCustomerClientId(clientId.trim());
        return user;
    }

    @Transactional(readOnly = true)
    public List<User> getAll() {

        UUID tenantId = currentTenant();

        return userRepository
                .findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {

        validateId(id);

        UUID tenantId = currentTenant();

        return userRepository
                .findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User not found: " + id));
    }

    @Transactional
    public User changeRole(
            UUID id,
            RoleName roleName) {

        validateId(id);

        if (roleName == null) {
            throw new IllegalArgumentException(
                    "Role is required");
        }

        UUID tenantId = currentTenant();

        User user = getById(id);

        /*
         * Prevent an administrator from silently removing the final
         * administrator account from the workspace.
         */
        if (ADMIN_ROLE.equals(user.getRole())
                && !ADMIN_ROLE.equals(roleName.name())
                && user.isActive()) {

            ensureAnotherActiveAdminExists(
                    tenantId,
                    user.getId());
        }

        user.setRole(roleName.name());

        /*
         * Role is security-sensitive. Existing JWTs must not remain valid
         * with the old authorization claims.
         */
        user.incrementTokenVersion();

        return userRepository.save(user);
    }

    @Transactional
    public User updateEmail(
            UUID id,
            String email) {

        validateId(id);

        User user = getById(id);

        String normalizedEmail = normalizeEmail(email);

        if (normalizedEmail.equals(user.getEmail())) {
            return user;
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException(
                    "Email already exists: " + normalizedEmail);
        }

        user.setEmail(normalizedEmail);

        /*
         * Email is the login identity, so changing it invalidates existing
         * authentication tokens.
         */
        user.incrementTokenVersion();

        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException(
                    "Email already exists: " + normalizedEmail);
        }
    }

    /**
     * Administrative password reset.
     *
     * The administrator does not need the user's current password.
     * The caller's authorization is enforced by UserController/SecurityConfig.
     */

    @Transactional
    public User changeOwnPassword(UUID id, String currentPassword, String newPassword) {
        User user = getById(id);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) throw new IllegalArgumentException("Current password is incorrect");
        validatePassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.incrementTokenVersion();
        return userRepository.save(user);
    }
    @Transactional
    public User updatePassword(
            UUID id,
            String newPassword) {

        validateId(id);
        validatePassword(newPassword);

        User user = getById(id);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.incrementTokenVersion();

        return userRepository.save(user);
    }

    /**
     * Deactivates rather than physically deleting a user.
     *
     * Historical audit records and operational records must continue
     * referencing the original user.
     */
    @Transactional
    public User deactivate(UUID id) {

        validateId(id);

        UUID tenantId = currentTenant();

        User user = getById(id);

        if (!user.isActive()) {
            return user;
        }

        /*
         * An administrator cannot disable their own account.
         */
        if (isCurrentAuthenticatedUser(user.getId())) {
            throw new IllegalStateException(
                    "You cannot deactivate your own account");
        }

        /*
         * Never allow the workspace to lose its final active administrator.
         */
        if (ADMIN_ROLE.equals(user.getRole())) {
            ensureAnotherActiveAdminExists(
                    tenantId,
                    user.getId());
        }

        user.setActive(false);

        /*
         * Immediately invalidate existing JWTs.
         */
        user.incrementTokenVersion();

        return userRepository.save(user);
    }

    @Transactional
    public User reactivate(UUID id) {

        validateId(id);

        User user = getById(id);

        if (user.isActive()) {
            return user;
        }

        user.setActive(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        /*
         * Any previously issued token remains invalid.
         */
        user.incrementTokenVersion();

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<Role> getRoles() {

        return Arrays.stream(RoleName.values())
                .map(Role::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countActiveUsers() {

        return userRepository.countByTenantIdAndActiveTrue(
                currentTenant());
    }

    private void ensureAnotherActiveAdminExists(
            UUID tenantId,
            UUID excludedUserId) {

        long activeAdmins = userRepository.countByTenantIdAndRoleAndActiveTrue(
                tenantId,
                ADMIN_ROLE);

        /*
         * If there is only one active administrator and that administrator
         * is the account being changed, the operation would leave the
         * workspace without an administrator.
         */
        if (activeAdmins <= 1) {
            throw new IllegalStateException(
                    "The last active administrator cannot be removed or deactivated");
        }
    }

    private UUID currentTenant() {

        if (!TenantContext.isSet()) {
            throw new IllegalStateException(
                    "No tenant is available for this request");
        }

        return TenantContext.getTenantId();
    }

    private boolean isCurrentAuthenticatedUser(UUID userId) {

        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null) {
            return false;
        }

        Object principal = authentication.getPrincipal();

        if (principal instanceof com.logiplatform.security.TenantPrincipal tenantPrincipal) {
            return userId.equals(
                    tenantPrincipal.userId());
        }

        return false;
    }

    private String normalizeEmail(String email) {

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException(
                    "Email is required");
        }

        String normalized = email.trim().toLowerCase(Locale.ROOT);

        if (normalized.length() > 255) {
            throw new IllegalArgumentException(
                    "Email is too long");
        }

        if (!normalized.matches(
                "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@"
                        + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}"
                        + "[A-Za-z0-9])?"
                        + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}"
                        + "[A-Za-z0-9])?)+$")) {
            throw new IllegalArgumentException(
                    "Invalid email address");
        }

        return normalized;
    }

    private void validatePassword(String password) {

        if (password == null || password.length() < 10) {
            throw new IllegalArgumentException(
                    "Password must contain at least 10 characters");
        }

        if (password.length() > 128) {
            throw new IllegalArgumentException(
                    "Password must not exceed 128 characters");
        }

        if (!password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException(
                    "Password must contain at least one uppercase letter");
        }

        if (!password.matches(".*[a-z].*")) {
            throw new IllegalArgumentException(
                    "Password must contain at least one lowercase letter");
        }

        if (!password.matches(".*\\d.*")) {
            throw new IllegalArgumentException(
                    "Password must contain at least one digit");
        }

        if (!password.matches(".*[^A-Za-z0-9].*")) {
            throw new IllegalArgumentException(
                    "Password must contain at least one special character");
        }
    }

    private void validateId(UUID id) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "User ID is required");
        }
    }
}
