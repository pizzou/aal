package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Server-owned, immutable, tenant-isolated POD evidence. */
@Service
public class PodEvidenceService {
    private static final long MAX_SIZE = 8L * 1024 * 1024;
    private static final String PREFIX = "/api/operations/pod-evidence/";
    private final JdbcTemplate db;
    private final DocumentSecurityService security;

    public PodEvidenceService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                              DocumentSecurityService security) {
        this.db = db;
        this.security = security;
    }

    @Transactional
    public UploadedEvidence upload(UUID shipmentId, MultipartFile file) {
        UUID tenant = tenant();
        if (shipmentId == null || file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shipment and evidence file are required");
        if (file.getSize() > MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Evidence exceeds 8 MB");
        boolean exists = Boolean.TRUE.equals(db.query(
                "SELECT EXISTS(SELECT 1 FROM shipments WHERE tenant_id=? AND id=?)",
                rs -> rs.next() && rs.getBoolean(1), tenant, shipmentId));
        if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");

        final byte[] content;
        try { content = file.getBytes(); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read evidence file", e); }
        if (content.length == 0 || content.length > MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Evidence file is empty or too large");
        String mime = normalizedMime(file.getContentType());
        if (!matchesSignature(content, mime))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Upload a genuine JPEG, PNG or PDF file");

        security.requireCleanEvidence(content);
        String checksum = checksum(content);
        String name = safeFilename(file.getOriginalFilename());
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO pod_evidence_files
                    (id,tenant_id,shipment_id,filename,mime_type,size_bytes,checksum_sha256,content)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,shipment_id,checksum_sha256) DO NOTHING
                """, id, tenant, shipmentId, name, mime, content.length, checksum, content);
        UUID persisted = db.query("""
                SELECT id FROM pod_evidence_files
                 WHERE tenant_id=? AND shipment_id=? AND checksum_sha256=?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenant, shipmentId, checksum);
        if (persisted == null) throw new IllegalStateException("POD evidence was not persisted");
        return new UploadedEvidence(persisted, PREFIX + persisted, mime);
    }

    @Transactional(readOnly = true)
    public DownloadEvidence download(UUID evidenceId) {
        UUID tenant = tenant();
        DownloadEvidence result = db.query("""
                SELECT filename,mime_type,content
                  FROM pod_evidence_files
                 WHERE tenant_id=? AND id=? AND scanner_status='CLEAN'
                """, rs -> rs.next() ? new DownloadEvidence(
                        rs.getString(1), rs.getString(2), rs.getBytes(3)) : null,
                tenant, evidenceId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found");
        return result;
    }

    @Transactional(readOnly = true)
    public void assertOwnedByShipment(UUID shipmentId, String evidenceUri) {
        if (evidenceUri == null || !evidenceUri.startsWith(PREFIX))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "POD requires a file uploaded through the secure Delivery screen");
        UUID id;
        try { id = UUID.fromString(evidenceUri.substring(PREFIX.length())); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid POD evidence reference");
        }
        UUID tenant = tenant();
        boolean valid = Boolean.TRUE.equals(db.query("""
                SELECT EXISTS(SELECT 1 FROM pod_evidence_files
                 WHERE tenant_id=? AND shipment_id=? AND id=? AND scanner_status='CLEAN')
                """, rs -> rs.next() && rs.getBoolean(1), tenant, shipmentId, id));
        if (!valid) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Delivery evidence is missing, unverified or belongs to a different shipment");
    }

    private static UUID tenant() {
        UUID value = TenantContext.getTenantId();
        if (value == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tenant context is required");
        return value;
    }

    private static String normalizedMime(String mime) {
        return mime == null ? "" : mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static boolean matchesSignature(byte[] b, String mime) {
        return switch (mime) {
            case "image/png" -> b.length >= 8 && (b[0]&255)==0x89 && b[1]=='P' && b[2]=='N' && b[3]=='G'
                    && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10;
            case "image/jpeg" -> b.length >= 3 && (b[0]&255)==0xff && (b[1]&255)==0xd8 && (b[2]&255)==0xff;
            case "application/pdf" -> b.length >= 5 && b[0]=='%' && b[1]=='P' && b[2]=='D' && b[3]=='F' && b[4]=='-';
            default -> false;
        };
    }

    static String safeFilename(String supplied) {
        if (supplied == null || supplied.isBlank()) return "delivery-evidence";
        String name = supplied.replace('\\','/');
        name = name.substring(name.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank()) name = "delivery-evidence";
        return name.substring(0, Math.min(160, name.length()));
    }

    private static String checksum(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception e) { throw new IllegalStateException("Unable to hash evidence", e); }
    }

    public record UploadedEvidence(UUID id, String evidenceUri, String mimeType) {}
    public record DownloadEvidence(String filename, String mimeType, byte[] content) {}
}
