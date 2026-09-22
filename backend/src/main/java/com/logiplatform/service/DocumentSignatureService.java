package com.logiplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

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

    public DocumentSignatureService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            ObjectMapper mapper,
            @Value("${document-signature.provider:${DOCUMENT_SIGNATURE_PROVIDER:INTERNAL_AUDIT}}") String provider,
            @Value("${document-signature.webhook-secret:${DOCUMENT_SIGNATURE_WEBHOOK_SECRET:}}") String webhookSecret) {
        this.db=db;
        this.mapper=mapper;
        this.provider=provider;
        this.webhookSecret=webhookSecret;
    }

    public Map<String,Object> request(UUID documentId, String signerName, String signerEmail) {
        UUID tenant=TenantContext.getTenantId();
        Map<String,Object> document=db.queryForMap("SELECT id,approval_status,scan_status FROM cargo_documents WHERE tenant_id=? AND id=?",tenant,documentId);
        if ("INFECTED".equalsIgnoreCase(String.valueOf(document.get("scan_status")))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Infected documents cannot be sent for signature");
        }
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO document_signature_requests(id,tenant_id,document_id,signer_name,signer_email,status,provider) VALUES(?,?,?,?,?,?,?)",
                id,tenant,documentId,signerName,signerEmail,"REQUESTED",provider);
        return db.queryForMap("SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=?",tenant,id);
    }

    public Map<String,Object> complete(UUID requestId, String signatureReference) {
        UUID tenant=TenantContext.getTenantId();
        Map<String,Object> row=db.queryForMap("SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=?",tenant,requestId);
        String signer=String.valueOf(row.get("signer_name"));
        String email=String.valueOf(row.getOrDefault("signer_email",""));
        Instant signedAt=Instant.now();
        String reference=signatureReference==null||signatureReference.isBlank()?UUID.randomUUID().toString():signatureReference.trim();
        String seal=sha256(row.get("document_id")+"|"+signer+"|"+email+"|"+reference+"|"+signedAt.toString());
        db.update("UPDATE document_signature_requests SET status='SIGNED',signature_reference=?,signature_hash=?,signed_at=? WHERE tenant_id=? AND id=?",
                reference,seal,signedAt,tenant,requestId);
        db.update("UPDATE cargo_documents SET approval_status='APPROVED',updated_at=now() WHERE tenant_id=? AND id=?",
                tenant,row.get("document_id"));
        return db.queryForMap("SELECT * FROM document_signature_requests WHERE tenant_id=? AND id=?",tenant,requestId);
    }

    public Map<String,Object> webhook(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Document signature webhook secret is not configured");
        }
        if (!secureEquals(hmac(payload, webhookSecret), signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid signature webhook authentication");
        }
        try {
            Map<String,Object> event=mapper.readValue(payload, new TypeReference<Map<String,Object>>() {});
            UUID requestId=UUID.fromString(String.valueOf(event.get("requestId")));
            UUID tenant=UUID.fromString(String.valueOf(event.get("tenantId")));
            String status=String.valueOf(event.getOrDefault("status","RECEIVED")).toUpperCase(Locale.ROOT);
            String ref=event.get("signatureReference")==null?null:String.valueOf(event.get("signatureReference"));
            int updated=db.update("UPDATE document_signature_requests SET status=?,signature_reference=COALESCE(?,signature_reference),signed_at=CASE WHEN ?='SIGNED' THEN COALESCE(signed_at,now()) ELSE signed_at END WHERE tenant_id=? AND id=?",
                    status,ref,status,tenant,requestId);
            return Map.of("requestId",requestId,"tenantId",tenant,"updated",updated>0,"status",status);
        } catch(ResponseStatusException ex) { throw ex; }
        catch(Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid signature webhook payload",ex); }
    }

    private static String hmac(String data,String secret){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] bytes=mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(); for(byte b:bytes) hex.append(String.format("%02x",b)); return hex.toString();
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private static boolean secureEquals(String a,String b){
        if(a==null||b==null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
    private static String sha256(String text){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder h=new StringBuilder(); for(byte b:bytes)h.append(String.format("%02x",b)); return h.toString();}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
