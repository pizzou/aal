package com.logiplatform.service;

import com.logiplatform.dto.SystemSettingsDtos.SettingRequest;
import com.logiplatform.dto.SystemSettingsDtos.SettingResponse;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SystemSettingsService {
    private final JdbcTemplate db;

    public SystemSettingsService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    public List<SettingResponse> list(String group) {
        UUID tenant = tenant();
        if (group == null || group.isBlank()) {
            return db.query("""
                    SELECT setting_group, setting_key, setting_value, updated_by, updated_at
                    FROM aal_system_settings
                    WHERE tenant_id=?
                    ORDER BY setting_group, setting_key
                    """, this::row, tenant);
        }
        return db.query("""
                SELECT setting_group, setting_key, setting_value, updated_by, updated_at
                FROM aal_system_settings
                WHERE tenant_id=? AND setting_group=?
                ORDER BY setting_key
                """, this::row, tenant, normalize(group));
    }

    @Transactional
    public SettingResponse save(SettingRequest request) {
        UUID tenant = tenant();
        UUID user = currentUser();
        String group = normalize(request.group());
        String key = normalize(request.key());
        String value = request.value() == null ? "" : request.value().trim();

        db.update("""
                INSERT INTO aal_system_settings(tenant_id,setting_group,setting_key,setting_value,updated_by,updated_at)
                VALUES(?,?,?,?,?,now())
                ON CONFLICT(tenant_id,setting_group,setting_key)
                DO UPDATE SET setting_value=EXCLUDED.setting_value,updated_by=EXCLUDED.updated_by,updated_at=now()
                """, tenant, group, key, value, user);
        return db.queryForObject("""
                SELECT setting_group, setting_key, setting_value, updated_by, updated_at
                FROM aal_system_settings WHERE tenant_id=? AND setting_group=? AND setting_key=?
                """, this::row, tenant, group, key);
    }

    @Transactional
    public void delete(String group, String key) {
        db.update("DELETE FROM aal_system_settings WHERE tenant_id=? AND setting_group=? AND setting_key=?",
                tenant(), normalize(group), normalize(key));
    }

    private SettingResponse row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new SettingResponse(
                rs.getString("setting_group"),
                rs.getString("setting_key"),
                rs.getString("setting_value"),
                rs.getObject("updated_by") == null ? null : rs.getString("updated_by"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant().toString());
    }

    private UUID tenant() {
        if (!TenantContext.isSet()) throw new IllegalStateException("No tenant is available for this request");
        return TenantContext.getTenantId();
    }

    private UUID currentUser() {
        Object principal = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication() == null ? null : org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        if (principal instanceof com.logiplatform.security.TenantPrincipal tp) return tp.userId();
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }
}
