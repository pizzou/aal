package com.logiplatform.controller;

import com.logiplatform.dto.PageResponse;
import com.logiplatform.model.UserNotification;
import com.logiplatform.security.TenantPrincipal;
import com.logiplatform.service.UserNotificationService;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class UserNotificationController {
    private final UserNotificationService service;

    public UserNotificationController(UserNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<UserNotification> list(Authentication a, Pageable pageable) {
        return PageResponse.from(service.list(((TenantPrincipal) a.getPrincipal()).userId(), pageable));
    }

    @GetMapping("/unread-count")
    public long unread(Authentication a) {
        return service.unread(((TenantPrincipal) a.getPrincipal()).userId());
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable UUID id, Authentication a) {
        service.markRead(id, ((TenantPrincipal) a.getPrincipal()).userId());
    }
}
