package com.logiplatform.dto;

import com.logiplatform.model.AuditLog;
import java.time.Instant;
import java.util.UUID;

public final class AuditDtos {
    private AuditDtos() {}
    public record Response(UUID id, UUID userId, String action, String resourceType, UUID resourceId,
                           String method, String path, String ipAddress, String userAgent,
                           Integer statusCode, boolean success, Instant createdAt) {
        public static Response from(AuditLog x) {
            return new Response(x.getId(), x.getUser()==null?null:x.getUser().getId(), x.getAction(), x.getResourceType(),
                    x.getResourceId(), x.getMethod(), x.getPath(), x.getIpAddress(), x.getUserAgent(),
                    x.getStatusCode(), x.isSuccess(), x.getCreatedAt());
        }
    }
}
