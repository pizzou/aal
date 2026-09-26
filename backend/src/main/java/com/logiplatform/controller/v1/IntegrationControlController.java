package com.logiplatform.controller.v1;

import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.integration.cargo_xml.CargoXmlMessageRegistry;
import com.logiplatform.integration.onerecord.OneRecordEventService;
import com.logiplatform.integration.security.IntegrationCredentialService;
import com.logiplatform.integration.control.ProviderHealthService;
import com.logiplatform.service.control.ReconciliationTaskService;
import com.logiplatform.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationControlController {
    private final IntegrationCredentialService credentials; private final ProviderHealthService health; private final AirCargoProviderRegistry providers; private final ReconciliationTaskService reconciliation; private final OneRecordEventService oneRecordEvents; private final CargoXmlMessageRegistry cargoXml;
    public IntegrationControlController(IntegrationCredentialService credentials,ProviderHealthService health,AirCargoProviderRegistry providers,ReconciliationTaskService reconciliation,OneRecordEventService oneRecordEvents,CargoXmlMessageRegistry cargoXml){this.credentials=credentials;this.health=health;this.providers=providers;this.reconciliation=reconciliation;this.oneRecordEvents=oneRecordEvents;this.cargoXml=cargoXml;}
    @GetMapping("/health") public Map<String,Object> health(){return health.current();}
    @GetMapping("/providers") public Map<String,Object> providers(){return Map.of("active",providers.active().providerCode(),"codes",providers.codes(),"capabilities",providers.capabilities());}
    @GetMapping("/credentials") public List<Map<String,Object>> credentials(){return credentials.inventory();}
    @PostMapping("/accounts") public Map<String,Object> account(@Valid @RequestBody AccountRequest r){UUID id=credentials.registerAccount(r.code(),r.providerName(),r.protocol(),r.baseUrl(),r.enabled(),r.capabilitiesJson());return Map.of("id",id,"code",r.code().toUpperCase(Locale.ROOT));}
    @PostMapping("/accounts/{accountId}/credentials") public Map<String,Object> rotate(@PathVariable UUID accountId,@Valid @RequestBody CredentialRequest r){return credentials.rotate(accountId,r.credentialType(),r.secret(),r.expiresAt(),null,r.metadataJson());}
    @DeleteMapping("/accounts/{accountId}/credentials/{type}") public ResponseEntity<Void> revoke(@PathVariable UUID accountId,@PathVariable String type){credentials.revoke(accountId,type);return ResponseEntity.noContent().build();}
    @GetMapping("/reconciliation") public Map<String,Object> reconciliation(@RequestParam(defaultValue="50") int limit){return Map.of("metrics",reconciliation.metrics(),"queue",reconciliation.queue(limit));}
    @GetMapping("/onerecord/events") public List<Map<String,Object>> oneRecordEvents(@RequestParam(defaultValue="50") int limit){return oneRecordEvents.recent(limit);}
    @GetMapping("/cargo-xml/registry") public Map<String,String> cargoXmlRegistry(){return cargoXml.all();}
    public record AccountRequest(@NotBlank String code,@NotBlank String providerName,@NotBlank String protocol,String baseUrl,boolean enabled,String capabilitiesJson){}
    public record CredentialRequest(@NotBlank String credentialType,@NotBlank String secret,Instant expiresAt,String metadataJson){}
}
