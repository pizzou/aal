package com.logiplatform.controller;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/platform/health")
public class SystemHealthController {
    private final HealthEndpoint health;
    public SystemHealthController(HealthEndpoint health) { this.health = health; }

    @GetMapping
    public Map<String,Object> health() {
        var component = health.health();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("status", component.getStatus().getCode());
        out.put("healthy", Status.UP.equals(component.getStatus()));
        out.put("checkedAt", Instant.now());
        if (component instanceof Health h) out.put("components", h.getDetails());
        else if (component instanceof CompositeHealth c) out.put("components", c.getComponents());
        else out.put("components", Map.of());
        return out;
    }
}
