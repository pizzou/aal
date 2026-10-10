package com.logiplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class DocumentSignatureService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final String webhookSecret;
    private final String provider;
    private final UUID singleTenantId;
    private final DocumentSignatureWebhookProcessor webhookProcessor;

    public DocumentSignatureService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            ObjectMapper mapper,
            @Value("${document-signature.provider:${DOCUMENT_SIGNATURE_PROVIDER:DISABLED}}") String provider,
            @Value("${document-signature.webhook-secret:${DOCUMENT_SIGNATURE_WEBHOOK_SECRET:}}") String webhookSecret,
            @Value("${app.single-tenant.id}") UUID singleTenantId,
            DocumentSignatureWebhookProcessor webhookProcessor) {
        this.db = db;
        this.mapper = mapper;
        this.provider = provider == null || provider.isBlank() ? "DISABLED" : provider.trim().toUpperCase(Locale.ROOT);
        this.webhookSecret = webhookSecret;
        this.singleTenantId = singleTenantId;
        this.webhookProcessor = webhookProcessor;
    }

    @Transactional
    public Map<String,Object> request(UUID documentId, String signerName, String signerEmail) {
        UUID tenant = requireTenant();
        if ("DISABLED".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Electronic signature provider is disabled; configure a verified provider before requesting signatures");
        }
        if (!"INTERNAL_AUDIT".equals(provider)) {
            // Do not create a local REQUESTED row and imply that an external
            // envelope was sent when this deployment has no outbound provider adapter.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The configured electronic-signature provider has no outbound dispatch adapter in this build");
        }
        if (documentId == null || signerName == null || signerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document and signer name are required");
        }
        Map<String,Object> document = db.queryForMap(
                "SELECT id,approval_status,scan_status FROM cargo_documents WHERE tenant_id=? AND id=? FOR UPDATE",
                tenant, documentId);
        String scanStatus = Objects.toString(document.get("scan_status"), "").toUpperCase(Locale.ROOT);
        if (!"CLEAN".equals(scanStatus)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only documents with a successful malware scan may be sent for signature");
        }
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO document_signature_requests
                    (id,tenant_id,document_id,signer_name,signer_email,status,provider)
                VALUES (?,?,?,?,?,'REQUESTED',?)
                """, id, tenant, documentId, signerName.trim(), normalizeEmail(signerEmail), provider);
        return db.queryForMap("SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=?", tenant, id);
    }

    /**
     * Manual completion exists only for the explicitly named INTERNAL_AUDIT mode.
     * Real external-provider signatures must arrive through the HMAC-verified webhook.
     */
    @Transactional
    public Map<String,Object> complete(UUID requestId, String signatureReference) {
        UUID tenant = requireTenant();
        if (!"INTERNAL_AUDIT".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "External provider signatures must be completed through the authenticated provider webhook");
        }
        String reference = signatureReference == null ? "" : signatureReference.trim();
        if (reference.isBlank() || reference.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A valid internal signature/attestation reference is required");
        }
        Map<String,Object> row = db.queryForMap(
                "SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=? FOR UPDATE",
                tenant, requestId);
        String previous = Objects.toString(row.get("status"), "").toUpperCase(Locale.ROOT);
        if ("SIGNED".equals(previous)) {
            if (reference.equals(Objects.toString(row.get("signature_reference"), ""))) return row;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signature request has already been completed with different evidence");
        }
        if (!Set.of("REQUESTED", "SENT").contains(previous)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signature request is not in a signable state");
        }
        ensureDocumentStillClean(tenant, asUuid(row.get("document_id")));
        Instant signedAt = Instant.now();
        UUID documentId = asUuid(row.get("document_id"));
        String seal = sha256(documentId + "|" + row.get("signer_name") + "|"
                + Objects.toString(row.get("signer_email"), "") + "|" + reference + "|" + signedAt);
        int updated = db.update("""
                UPDATE document_signature_requests
                   SET status='SIGNED', signature_reference=?, signature_hash=?, signed_at=?
                 WHERE tenant_id=? AND id=? AND status IN ('REQUESTED','SENT')
                """, reference, seal, signedAt, tenant, requestId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Signature request changed concurrently; reload and verify its current state");
        }
        int approved = db.update("UPDATE cargo_documents SET approval_status='APPROVED',updated_at=now() WHERE tenant_id=? AND id=? AND scan_status='CLEAN'",
                tenant, documentId);
        if (approved != 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Document is no longer clean and cannot be approved");
        }
        return db.queryForMap("SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=?", tenant, requestId);
    }

    /**
     * HMAC verification and tenant selection happen before the processor's
     * transaction starts. This ordering is essential: the tenant-aware data
     * source reads TenantContext when it checks out the transactional connection.
     */
    public Map<String,Object> webhook(String payload, String signature) {
        if ("DISABLED".equals(provider) || "INTERNAL_AUDIT".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "A real electronic-signature provider must be configured before webhooks are accepted");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Document signature webhook secret is not configured");
        }
        if (payload == null || payload.isBlank() || !secureEquals(hmac(payload, webhookSecret), signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid signature webhook authentication");
        }

        final Map<String,Object> event;
        try {
            event = mapper.readValue(payload, new TypeReference<Map<String,Object>>() {});
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature webhook payload", ex);
        }

        UUID requestId = parseUuid(event.get("requestId"), "requestId");
        UUID tenant = parseUuid(event.get("tenantId"), "tenantId");
        if (!singleTenantId.equals(tenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Signature webhook tenant is not configured for this deployment");
        }
        String status = Objects.toString(event.get("status"), "").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SENT", "SIGNED", "DECLINED", "CANCELLED", "EXPIRED", "FAILED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported signature provider status");
        }
        String reference = event.get("signatureReference") == null ? null : String.valueOf(event.get("signatureReference")).trim();
        if ("SIGNED".equals(status) && (reference == null || reference.isBlank() || reference.length() > 255)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A verified signature reference is required for SIGNED events");
        }

        UUID previousTenant = TenantContext.isSet() ? TenantContext.getTenantId() : null;
        try {
            // The processor is another Spring bean; its @Transactional proxy is
            // invoked only after the verified tenant has been installed.
            TenantContext.setTenantId(tenant);
            return webhookProcessor.process(requestId, tenant, provider, status, reference);
        } finally {
            if (previousTenant == null) TenantContext.clear();
            else TenantContext.setTenantId(previousTenant);
        }
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

    private UUID requireTenant() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is required");
        if (!singleTenantId.equals(tenant)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant is not authorized");
        return tenant;
    }

    private static UUID parseUuid(Object value, String field) {
        try { return UUID.fromString(Objects.toString(value, "")); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field, ex);
        }
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID id) return id;
        return parseUuid(value, "documentId");
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid signer email is required");
        }
        return normalized;
    }

    private static String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify signature webhook", ex);
        }
    }

    private static boolean secureEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.trim().getBytes(StandardCharsets.UTF_8));
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
