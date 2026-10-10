package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Applies an already HMAC-authenticated signature-provider event atomically.
 * The caller must install the verified tenant in TenantContext before calling
 * this proxied service, so PostgreSQL RLS is initialized for the right tenant
 * when the transaction obtains its connection.
 */
@Service
public class DocumentSignatureWebhookProcessor {
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("SIGNED", "DECLINED", "CANCELLED", "EXPIRED", "FAILED");

    private final JdbcTemplate db;

    public DocumentSignatureWebhookProcessor(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Map<String,Object> process(UUID requestId, UUID tenant, String expectedProvider, String status, String reference) {
        if (tenant == null || !tenant.equals(TenantContext.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Verified tenant context is missing or does not match the signature event");
        }

        Map<String,Object> row = db.queryForMap(
                "SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=? FOR UPDATE",
                tenant, requestId);
        String requestProvider = Objects.toString(row.get("provider"), "").trim();
        if (expectedProvider == null || expectedProvider.isBlank()
                || !expectedProvider.equalsIgnoreCase(requestProvider)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signature event provider does not match the stored signature request provider");
        }
        String current = Objects.toString(row.get("status"), "").toUpperCase(Locale.ROOT);
        String priorReference = Objects.toString(row.get("signature_reference"), "");
        if (TERMINAL_STATUSES.contains(current)) {
            if (current.equals(status) && (reference == null || reference.isBlank() || reference.equals(priorReference))) {
                return Map.of("requestId", requestId, "tenantId", tenant, "updated", false, "status", current);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A terminal signature request cannot transition to a different status");
        }
        if (!Set.of("REQUESTED", "SENT").contains(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signature request is not in a provider-completable state");
        }

        UUID documentId = asUuid(row.get("document_id"));
        Instant signedAt = "SIGNED".equals(status) ? Instant.now() : null;
        String seal = "SIGNED".equals(status)
                ? sha256(documentId + "|" + row.get("signer_name") + "|"
                        + Objects.toString(row.get("signer_email"), "") + "|" + reference + "|" + signedAt)
                : null;
        if ("SIGNED".equals(status)) ensureDocumentStillClean(tenant, documentId);

        int changed = db.update("""
                UPDATE document_signature_requests
                   SET status=?, signature_reference=COALESCE(?,signature_reference),
                       signature_hash=COALESCE(?,signature_hash),
                       signed_at=CASE WHEN ?='SIGNED' THEN COALESCE(signed_at,?) ELSE signed_at END
                 WHERE tenant_id=? AND id=? AND status IN ('REQUESTED','SENT')
                """, status, reference, seal, status, signedAt, tenant, requestId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signature request changed concurrently; provider event requires reconciliation");
        }
        if ("SIGNED".equals(status)) {
            int approved = db.update("""
                    UPDATE cargo_documents SET approval_status='APPROVED',updated_at=now()
                     WHERE tenant_id=? AND id=? AND scan_status='CLEAN'
                    """, tenant, documentId);
            if (approved != 1) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Document is no longer clean and cannot be approved");
            }
        }
        return Map.of("requestId", requestId, "tenantId", tenant, "updated", true, "status", status);
    }

    private void ensureDocumentStillClean(UUID tenant, UUID documentId) {
        List<Map<String,Object>> documents = db.queryForList(
                "SELECT id FROM cargo_documents WHERE tenant_id=? AND id=? AND scan_status='CLEAN' FOR UPDATE",
                tenant, documentId);
        if (documents.size() != 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Document is no longer present with a successful malware scan");
        }
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID id) return id;
        try { return UUID.fromString(Objects.toString(value, "")); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored signature request contains an invalid document id", ex);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create signature audit hash", ex);
        }
    }
}
