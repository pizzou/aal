package com.logiplatform.security;

import java.util.UUID;

public record TenantPrincipal(UUID userId, UUID tenantId, String role) {}
