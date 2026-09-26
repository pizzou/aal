package com.logiplatform.integration.security;

import com.logiplatform.service.AirlineIntegrationAttemptService;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class WebhookSecurityService {
    private final JdbcTemplate db;
    private final String defaultSecret;
    private final long maxSkewSeconds;

    public WebhookSecurityService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db,
                                  @Value("${aircargo.webhook.secret:}") String defaultSecret,
                                  @Value("${integration.webhook.max-clock-skew-seconds:300}") long maxSkewSeconds) {
        this.db=db; this.defaultSecret=defaultSecret==null?"":defaultSecret.trim(); this.maxSkewSeconds=Math.max(1,maxSkewSeconds);
    }

    public Verification verify(String provider, String body, String signature, String timestamp, String nonce, String eventId) {
        if (provider == null || provider.isBlank() || body == null || signature == null || timestamp == null || eventId == null || eventId.isBlank()) return Verification.reject("MISSING_SECURITY_HEADERS");
        long epoch; try { epoch=Long.parseLong(timestamp); } catch (NumberFormatException ex) { return Verification.reject("INVALID_TIMESTAMP"); }
        if (Math.abs(Instant.now().getEpochSecond()-epoch)>maxSkewSeconds) return Verification.reject("CLOCK_SKEW");
        String secret=defaultSecret;
        if (secret.isBlank()) return Verification.reject("WEBHOOK_SECRET_NOT_CONFIGURED");
        String expected=sign(secret,timestamp,nonce,eventId,body);
        if (!MessageDigest.isEqual(normalize(signature).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) return Verification.reject("INVALID_SIGNATURE");
        String hash=AirlineIntegrationAttemptService.hash(body);
        UUID tenant=TenantContext.getTenantId();
        try {
            int inserted=db.update("INSERT INTO integration_webhook_events(id,tenant_id,provider_code,event_id,nonce,signature,body_hash,status) VALUES(gen_random_uuid(),?,?,?,?,?,?,'RECEIVED') ON CONFLICT DO NOTHING", tenant, provider.toUpperCase(Locale.ROOT), eventId, nonce, normalize(signature), hash);
            if (inserted==0) return Verification.reject("DUPLICATE_EVENT");
        } catch (Exception ex) { return Verification.reject("WEBHOOK_REPLAY_STORE_UNAVAILABLE"); }
        return new Verification(true,"ACCEPTED",hash);
    }

    public void processed(String provider, String eventId, boolean success, String error) {
        db.update("UPDATE integration_webhook_events SET status=?,processed_at=now(),error_detail=? WHERE tenant_id=? AND provider_code=? AND event_id=?", success?"PROCESSED":"FAILED", error, TenantContext.getTenantId(), provider.toUpperCase(Locale.ROOT), eventId);
    }

    private static String sign(String secret,String timestamp,String nonce,String eventId,String body) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); byte[] d=mac.doFinal((timestamp+"."+(nonce==null?"":nonce)+"."+eventId+"."+body).getBytes(StandardCharsets.UTF_8)); StringBuilder out=new StringBuilder(); for(byte b:d) out.append(String.format("%02x",b)); return out.toString(); }
        catch(Exception ex){ throw new IllegalStateException("Unable to verify webhook",ex); }
    }
    private static String normalize(String signature){String s=signature.trim(); return s.regionMatches(true,0,"sha256=",0,7)?s.substring(7):s;}
    public record Verification(boolean accepted,String reason,String bodyHash){ static Verification reject(String reason){return new Verification(false,reason,null);} }
}
