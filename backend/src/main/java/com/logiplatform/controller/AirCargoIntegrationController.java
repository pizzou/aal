package com.logiplatform.controller;

import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.service.AirlineDeadLetterService;
import com.logiplatform.service.AirlineIntegrationAttemptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/air-cargo/integration")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','OPERATIONS','AIR_CARGO')")
public class AirCargoIntegrationController {
    private final AirCargoProviderRegistry providers; private final AirlineDeadLetterService deadLetters; private final AirlineIntegrationAttemptService attempts;
    public AirCargoIntegrationController(AirCargoProviderRegistry providers,AirlineDeadLetterService deadLetters,AirlineIntegrationAttemptService attempts){this.providers=providers;this.deadLetters=deadLetters;this.attempts=attempts;}
    @GetMapping("/health") public Map<String,Object> health(){var p=providers.active();Map<String,Object> out=new LinkedHashMap<>();out.put("provider",p.providerCode());out.put("capabilities",p.capabilities());out.put("deadLetters",deadLetters.list().size());out.put("attempts",attempts.health(p.providerCode()));return out;}
    @GetMapping("/dead-letters") public List<Map<String,Object>> deadLetters(){return deadLetters.list();}
}
