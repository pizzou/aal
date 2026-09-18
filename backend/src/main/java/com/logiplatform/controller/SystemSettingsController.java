package com.logiplatform.controller;

import com.logiplatform.dto.SystemSettingsDtos.SettingRequest;
import com.logiplatform.dto.SystemSettingsDtos.SettingResponse;
import com.logiplatform.service.SystemSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SystemSettingsController {
    private final SystemSettingsService service;

    public SystemSettingsController(SystemSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<SettingResponse> list(@RequestParam(required = false) String group) {
        return service.list(group);
    }

    @PutMapping
    public SettingResponse save(@Valid @RequestBody SettingRequest request) {
        return service.save(request);
    }

    @DeleteMapping
    public void delete(@RequestParam String group, @RequestParam String key) {
        service.delete(group, key);
    }
}
