package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;


@Service
public class FinancialDocumentArchiveService {
    private final JdbcTemplate db;
    private final JdbcTemplate publicDb;
    private final RestTemplate http = new RestTemplate();

    @Value("${financial-document.storage-provider:${FINANCIAL_DOCUMENT_STORAGE_PROVIDER:DATABASE}}")
    private String provider;

    @Value("${financial-document.supabase-url:${SUPABASE_URL:}}")
    private String supabaseUrl;

    @Value("${financial-document.supabase-service-key:${SUPABASE_SERVICE_ROLE_KEY:}}")
    private String supabaseServiceKey;

    @Value("${financial-document.supabase-bucket:${SUPABASE_FINANCIAL_DOCUMENT_BUCKET:financial-documents}}")
    private String supabaseBucket;

    @Value("${app.backend.url:}")
    private String backendUrl;

    public FinancialDocumentArchiveService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            @Qualifier("publicJdbcTemplate") JdbcTemplate publicDb) {
        this.db = db;
        this.publicDb = publicDb;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID archive(String documentType, UUID sourceId, String filename,
                        String mimeType, byte[] content) {
        UUID tenant = requireTenant();
        if (content == null || content.length == 0) throw new IllegalArgumentException("Document content is empty");

        String hash = sha256(content);
        UUID existing = db.query("""
                SELECT id FROM financial_document_archives
                 WHERE tenant_id=? AND document_type=? AND source_id=? AND sha256=?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenant, documentType, sourceId, hash);
        if (existing != null) return existing;

        String selected = provider == null ? "DATABASE" : provider.trim().toUpperCase();
        String objectKey = "tenant/" + tenant + "/" + documentType.toLowerCase() + "/" + sourceId + "/" + hash + ".pdf";

        if ("SUPABASE".equals(selected)) {
            uploadSupabase(objectKey, mimeType, content);
        } else if (!"DATABASE".equals(selected)) {
            throw new IllegalStateException("Unsupported financial document storage provider: " + selected);
        }

        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO financial_document_archives
                    (id,tenant_id,document_type,source_id,document_name,mime_type,
                     storage_provider,object_key,content,sha256,immutable)
                VALUES(?,?,?,?,?,?,?,?,?,?,true)
                ON CONFLICT(tenant_id,document_type,source_id,sha256) DO NOTHING
                """,
                id, tenant, documentType, sourceId, filename, mimeType,
                selected, objectKey, "DATABASE".equals(selected) ? content : null, hash);

        return db.query("""
                SELECT id FROM financial_document_archives
                 WHERE tenant_id=? AND document_type=? AND source_id=? AND sha256=?
                """,
                rs -> rs.next() ? rs.getObject(1, UUID.class) : id,
                tenant, documentType, sourceId, hash);
    }


    @Transactional(readOnly = true)
    public UUID latestArchiveId(String documentType, UUID sourceId) {
        UUID tenant=requireTenant();
        return db.query("""
                SELECT id FROM financial_document_archives
                 WHERE tenant_id=? AND document_type=? AND source_id=?
                 ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenant, documentType, sourceId);
    }

    @Transactional
    public String issueLink(UUID archiveId, Duration ttl) {
        UUID tenant = requireTenant();
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Link TTL must be positive");
        if (ttl.compareTo(Duration.ofDays(7)) > 0) throw new IllegalArgumentException("Financial document links may not exceed 7 days");

        boolean exists = Boolean.TRUE.equals(db.query("""
                SELECT EXISTS(SELECT 1 FROM financial_document_archives WHERE tenant_id=? AND id=?)
                """, (rs,n) -> rs.next() && rs.getBoolean(1), tenant, archiveId));
        if (!exists) throw new IllegalArgumentException("Financial document not found");

        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String hash = sha256(token.getBytes(StandardCharsets.UTF_8));
        db.update("INSERT INTO financial_document_links(id,tenant_id,archive_id,token_hash,expires_at) VALUES(?,?,?,?,?)",
                UUID.randomUUID(), tenant, archiveId, hash, java.sql.Timestamp.from(Instant.now().plus(ttl)));

        return (backendUrl == null || backendUrl.isBlank() ? "" : backendUrl.replaceAll("/$", "")) + "/api/finance/hardening/public/" + token;
    }


    @Transactional
    public void revokeLink(UUID linkId) {
        UUID tenant=requireTenant();
        int n=db.update("UPDATE financial_document_links SET revoked_at=now() WHERE tenant_id=? AND id=? AND revoked_at IS NULL",
                tenant,linkId);
        if(n==0) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Document link not found or already revoked");
    }

    @Transactional(readOnly = true)
    public StoredDocument downloadByToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Document token is required");
        String hash = sha256(token.getBytes(StandardCharsets.UTF_8));
        return publicDb.query("""
                SELECT a.id,a.document_name,a.mime_type,a.storage_provider,a.object_key,a.content
                  FROM financial_document_links l
                  JOIN financial_document_archives a ON a.id=l.archive_id AND a.tenant_id=l.tenant_id
                 WHERE l.token_hash=? AND l.revoked_at IS NULL AND l.expires_at>now() AND a.immutable=true
                """,
                rs -> {
                    if (!rs.next()) return null;
                    UUID id=rs.getObject(1,UUID.class);
                    String name=rs.getString(2), mime=rs.getString(3), storage=rs.getString(4), key=rs.getString(5);
                    byte[] content=rs.getBytes(6);
                    if ("SUPABASE".equalsIgnoreCase(storage)) content=downloadSupabase(key);
                    return content==null ? null : new StoredDocument(id,name,mime,content);
                }, hash);
    }

    private void uploadSupabase(String objectKey, String mimeType, byte[] content) {
        if (supabaseUrl == null || supabaseUrl.isBlank() || supabaseServiceKey == null || supabaseServiceKey.isBlank())
            throw new IllegalStateException("Supabase financial document storage is enabled but credentials are missing");

        String url = UriComponentsBuilder.fromUriString(supabaseUrl.replaceAll("/$",""))
                .path("/storage/v1/object/")
                .pathSegment(supabaseBucket)
                .path("/")
                .path(objectKey)
                .build().toUriString();

        HttpHeaders h=new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(mimeType));
        h.setBearerAuth(supabaseServiceKey);
        h.set("x-upsert","false");
        ResponseEntity<Void> response=http.exchange(url,HttpMethod.PUT,new HttpEntity<>(content,h),Void.class);
        if (!response.getStatusCode().is2xxSuccessful())
            throw new IllegalStateException("Supabase Storage rejected financial document upload: "+response.getStatusCode());
    }

    private byte[] downloadSupabase(String objectKey) {
        if (supabaseUrl == null || supabaseUrl.isBlank() || supabaseServiceKey == null || supabaseServiceKey.isBlank())
            throw new IllegalStateException("Supabase financial document storage credentials are missing");
        String url=UriComponentsBuilder.fromUriString(supabaseUrl.replaceAll("/$",""))
                .path("/storage/v1/object/")
                .pathSegment(supabaseBucket)
                .path("/")
                .path(objectKey).build().toUriString();
        HttpHeaders h=new HttpHeaders(); h.setBearerAuth(supabaseServiceKey);
        ResponseEntity<byte[]> response=http.exchange(url,HttpMethod.GET,new HttpEntity<>(h),byte[].class);
        if(!response.getStatusCode().is2xxSuccessful() || response.getBody()==null)
            throw new IllegalStateException("Unable to retrieve archived financial document");
        return response.getBody();
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e){ throw new IllegalStateException("Unable to hash financial document",e); }
    }

    private static UUID requireTenant() {
        UUID t=TenantContext.getTenantId();
        if(t==null) throw new IllegalStateException("Tenant context is required");
        return t;
    }

    public record StoredDocument(UUID id,String filename,String mimeType,byte[] content){}
}
