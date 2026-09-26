package com.logiplatform.integration.security;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.Base64;

/** Stores integration secrets encrypted at rest; plaintext is never returned to API callers. */
@Service
public class IntegrationCredentialService {
    private final JdbcTemplate db;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public IntegrationCredentialService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                        @Value("${integration.credentials.encryption-key:}") String configuredKey) {
        this.db = db;
        this.key = decodeKey(configuredKey);
    }

    @Transactional
    public UUID registerAccount(String code, String providerName, String protocol, String baseUrl, boolean enabled, String capabilitiesJson) {
        UUID tenant = TenantContext.getTenantId();
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO integration_accounts(id,tenant_id,code,provider_name,protocol,base_url,enabled,status,capabilities_json) VALUES(?,?,?,?,?,?,?,'PENDING',?) ON CONFLICT(tenant_id,code) DO UPDATE SET provider_name=EXCLUDED.provider_name,protocol=EXCLUDED.protocol,base_url=EXCLUDED.base_url,enabled=EXCLUDED.enabled,capabilities_json=EXCLUDED.capabilities_json,updated_at=now()",
                id, tenant, normalize(code), providerName, protocol, blankToNull(baseUrl), enabled, capabilitiesJson);
        return db.queryForObject("SELECT id FROM integration_accounts WHERE tenant_id=? AND code=?", UUID.class, tenant, normalize(code));
    }

    @Transactional
    public Map<String,Object> rotate(UUID accountId, String credentialType, String secret, Instant expiresAt, UUID createdBy, String metadataJson) {
        requireKey();
        UUID tenant = TenantContext.getTenantId();
        Integer next = db.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM integration_credential_versions WHERE tenant_id=? AND account_id=? AND credential_type=?", Integer.class, tenant, accountId, credentialType);
        db.update("UPDATE integration_credential_versions SET status='RETIRED',rotated_at=now() WHERE tenant_id=? AND account_id=? AND credential_type=? AND status='ACTIVE'", tenant, accountId, credentialType);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO integration_credential_versions(id,tenant_id,account_id,credential_type,version_no,status,encrypted_value,expires_at,created_by,metadata_json) VALUES(?,?,?,?,?,'ACTIVE',?,?,?,?)",
                id, tenant, accountId, credentialType, next, encrypt(secret), expiresAt, createdBy, metadataJson);
        return Map.of("id", id, "accountId", accountId, "credentialType", credentialType, "version", next, "status", "ACTIVE", "expiresAt", expiresAt == null ? "" : expiresAt.toString());
    }

    @Transactional
    public void revoke(UUID accountId, String credentialType) {
        db.update("UPDATE integration_credential_versions SET status='REVOKED',rotated_at=now() WHERE tenant_id=? AND account_id=? AND credential_type=? AND status='ACTIVE'", TenantContext.getTenantId(), accountId, credentialType);
    }

    public Optional<String> resolveActiveSecretByCode(String accountCode, String credentialType) {
        requireKey();
        UUID tenant = TenantContext.getTenantId();
        List<String> rows = db.query("SELECT c.encrypted_value FROM integration_credential_versions c JOIN integration_accounts a ON a.id=c.account_id AND a.tenant_id=c.tenant_id WHERE c.tenant_id=? AND a.code=? AND c.credential_type=? AND c.status='ACTIVE' AND (c.expires_at IS NULL OR c.expires_at>now()) ORDER BY c.version_no DESC LIMIT 1",
                (rs,n)->rs.getString(1), tenant, accountCode.trim().toUpperCase(Locale.ROOT), credentialType);
        return rows.stream().findFirst().map(this::decrypt);
    }

    public Optional<String> resolveActiveSecret(UUID accountId, String credentialType) {
        requireKey();
        UUID tenant = TenantContext.getTenantId();
        List<String> rows = db.query("SELECT encrypted_value FROM integration_credential_versions WHERE tenant_id=? AND account_id=? AND credential_type=? AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>now()) ORDER BY version_no DESC LIMIT 1",
                (rs,n)->rs.getString(1), tenant, accountId, credentialType);
        return rows.stream().findFirst().map(this::decrypt);
    }

    public List<Map<String,Object>> inventory() {
        UUID tenant = TenantContext.getTenantId();
        return db.queryForList("SELECT id,account_id,credential_type,version_no,status,expires_at,rotated_at,created_at FROM integration_credential_versions WHERE tenant_id=? ORDER BY account_id,credential_type,version_no DESC", tenant);
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal((plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv,0,packed,0,iv.length); System.arraycopy(ciphertext,0,packed,iv.length,ciphertext.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) { throw new IllegalStateException("Unable to encrypt integration credential", ex); }
    }

    private String decrypt(String value) {
        try {
            byte[] packed = Base64.getDecoder().decode(value); byte[] iv = Arrays.copyOfRange(packed,0,12); byte[] ciphertext = Arrays.copyOfRange(packed,12,packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Unable to decrypt integration credential", ex); }
    }

    private void requireKey() { if (key.length != 32) throw new IllegalStateException("AAL_CREDENTIAL_ENCRYPTION_KEY must be a base64-encoded 32-byte key"); }
    private static byte[] decodeKey(String value) { if (value == null || value.isBlank()) return new byte[0]; try { return Base64.getDecoder().decode(value.trim()); } catch (IllegalArgumentException ex) { return new byte[0]; } }
    private static String normalize(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Integration code is required"); return value.trim().toUpperCase(Locale.ROOT); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
