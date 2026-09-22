package com.logiplatform.service;

import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class DocumentSecurityService {
    private final JdbcTemplate db;
    private final String host;
    private final int port;
    private final int timeoutMs;
    private final boolean enabled;
    private final boolean required;
    private final String scannerVersion;

    public DocumentSecurityService(
            @Qualifier("tenantJdbcTemplate") JdbcTemplate db,
            @Value("${document-security.clamav.enabled:${CLAMAV_ENABLED:false}}") boolean enabled,
            @Value("${document-security.clamav.required:${CLAMAV_REQUIRED:false}}") boolean required,
            @Value("${document-security.clamav.host:${CLAMAV_HOST:localhost}}") String host,
            @Value("${document-security.clamav.port:${CLAMAV_PORT:3310}}") int port,
            @Value("${document-security.clamav.timeout-ms:${CLAMAV_TIMEOUT_MS:15000}}") int timeoutMs,
            @Value("${document-security.clamav.scanner-version:${CLAMAV_SCANNER_VERSION:clamav}}") String scannerVersion) {
        this.db = db;
        this.enabled = enabled;
        this.required = required;
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.scannerVersion = scannerVersion;
    }

    public Map<String,Object> scan(UUID shipmentId, UUID documentId, MultipartFile file) {
        UUID tenant = TenantContext.getTenantId();
        Map<String,Object> document = db.queryForMap(
                "SELECT id,shipment_id,mime_type,checksum_sha256,file_size_bytes FROM cargo_documents WHERE tenant_id=? AND shipment_id=? AND id=?",
                tenant, shipmentId, documentId);
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document file is empty");
        if (file.getSize() > 50L * 1024L * 1024L) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Document exceeds 50 MB security-scan limit");

        String checksum = sha256(file);
        try {
            db.update("UPDATE cargo_documents SET checksum_sha256=?,file_size_bytes=?,mime_type=?,scan_status=?,updated_at=now() WHERE tenant_id=? AND shipment_id=? AND id=?",
                    checksum,file.getSize(),file.getContentType(),"SCANNING",tenant,shipmentId,documentId);
            ScanResult result = enabled ? scanClamAv(file.getInputStream()) : new ScanResult(required ? "UNAVAILABLE" : "NOT_CONFIGURED", "ClamAV is not enabled");
            db.update("UPDATE cargo_documents SET scan_status=?,scanned_at=now(),scanner_version=?,updated_at=now() WHERE tenant_id=? AND id=?",result.status(),scannerVersion,tenant,documentId);
            if (required && !"CLEAN".equals(result.status())) {
                if (!"INFECTED".equals(result.status())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.detail());
            }
            return Map.of("documentId",documentId,"shipmentId",shipmentId,"checksumSha256",checksum,"status",result.status(),"detail",result.detail(),"scanner",scannerVersion,"scannedAt",Instant.now());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            db.update("UPDATE cargo_documents SET scan_status=?,scanned_at=now(),scanner_version=?,updated_at=now() WHERE tenant_id=? AND id=?","ERROR",scannerVersion,tenant,documentId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Document security scan failed", ex);
        }
    }

    private ScanResult scanClamAv(InputStream input) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[]{'z','I','N','S','T','R','E','A','M',0});
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(read).array());
                out.write(buffer, 0, read);
            }
            out.write(new byte[]{0,0,0,0});
            out.flush();
            String line = readLine(in);
            String normalized = line == null ? "" : line.trim();
            if (normalized.endsWith(": OK")) return new ScanResult("CLEAN", normalized);
            if (normalized.contains("FOUND")) return new ScanResult("INFECTED", normalized);
            return new ScanResult("ERROR", normalized.isBlank() ? "ClamAV returned no result" : normalized);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == 0) break;
            out.write(b);
            if (out.size() > 4096) break;
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read=input.read(buffer)) >= 0) if (read>0) digest.update(buffer,0,read);
            }
            StringBuilder hex=new StringBuilder();
            for(byte b:digest.digest()) hex.append(String.format("%02x",b));
            return hex.toString();
        } catch(Exception ex) { throw new IllegalStateException("Unable to hash document",ex); }
    }

    private record ScanResult(String status, String detail) {}
}
