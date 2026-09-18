package com.logiplatform.service;

import com.logiplatform.model.AuditLog;
import com.logiplatform.model.User;
import com.logiplatform.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import static com.logiplatform.dto.AuditDtos.Response;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogRepository repository;
    private final EntityManager entityManager;

    public AuditService(AuditLogRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Page<Response> list(Pageable pageable) {
        return repository.findAllByTenantIdOrderByCreatedAtDesc(com.logiplatform.tenancy.TenantContext.getTenantId(), pageable).map(Response::from);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, UUID userId, String action, String resourceType, UUID resourceId,
                       String method, String path, String ip, String userAgent, int status, boolean success) {
        if (tenantId == null) return;
        User user = userId == null ? null : entityManager.getReference(User.class, userId);
        repository.save(new AuditLog(tenantId, user, action, resourceType, resourceId, method, path,
                ip, userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 2000)), status, success));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID tenantId, UUID userId, String action, String resourceType, UUID resourceId, String method, String path, String ip, String userAgent, int status, boolean success, String before, String after, String correlation) {
        if (tenantId == null) return;
        User user = userId == null ? null : entityManager.getReference(User.class, userId);
        AuditLog row=new AuditLog(tenantId,user,action,resourceType,resourceId,method,path,ip,userAgent==null?null:userAgent.substring(0,Math.min(userAgent.length(),2000)),status,success);
        row.setState(before,after,correlation); repository.save(row);
    }
}
