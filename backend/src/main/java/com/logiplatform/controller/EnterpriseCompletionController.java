package com.logiplatform.controller;

import com.logiplatform.dto.EnterpriseCompletionDtos.*;
import com.logiplatform.service.EnterpriseCompletionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/platform")
public class EnterpriseCompletionController {
    private final EnterpriseCompletionService service;
    public EnterpriseCompletionController(EnterpriseCompletionService service){this.service=service;}

    @PostMapping("/mfa/{userId}/setup") public MfaSetupResponse setupMfa(@PathVariable UUID userId){return service.setupMfa(userId);}
    @PostMapping("/mfa/{userId}/verify") public MfaStatusResponse verifyMfa(@PathVariable UUID userId,@Valid @RequestBody MfaVerifyRequest r){return service.verifyMfa(userId,r.code());}
    @GetMapping("/mfa/{userId}") public MfaStatusResponse mfa(@PathVariable UUID userId){return service.mfaStatus(userId);}
    @DeleteMapping("/mfa/{userId}") public void disableMfa(@PathVariable UUID userId){service.disableMfa(userId);}

    @PostMapping("/fx-rates") public FxRateResponse fx(@Valid @RequestBody FxRateRequest r){return service.upsertFx(r);}
    @GetMapping("/fx-rates") public List<FxRateResponse> fx(@RequestParam LocalDate from,@RequestParam LocalDate to){return service.fxRates(from,to);}

    @PostMapping("/finance-periods") public PeriodResponse openPeriod(@Valid @RequestBody PeriodRequest r){return service.openPeriod(r);}
    @GetMapping("/finance-periods") public List<PeriodResponse> periods(){return service.periods();}
    @PostMapping("/finance-periods/{id}/close") public PeriodResponse closePeriod(@PathVariable UUID id){return service.closePeriod(id);}

    @PostMapping("/adjustments") public AdjustmentResponse adjustment(@Valid @RequestBody AdjustmentRequest r){return service.createAdjustment(r);}
    @PostMapping("/adjustments/{id}/approve") public AdjustmentResponse approveAdjustment(@PathVariable UUID id){return service.approveAdjustment(id);}
    @GetMapping("/adjustments") public List<AdjustmentResponse> adjustments(){return service.adjustments();}

    @PostMapping("/geofences") public GeofenceResponse geofence(@Valid @RequestBody GeofenceRequest r){return service.createGeofence(r);}
    @GetMapping("/geofences") public List<GeofenceResponse> geofences(){return service.geofences();}
    @GetMapping("/geofences/evaluate") public List<GeofenceEvaluation> evaluate(@RequestParam double latitude,@RequestParam double longitude){return service.evaluateGeofences(latitude,longitude);}

    @PatchMapping("/legs/{legId}") public void updateLeg(@PathVariable UUID legId,@RequestBody LegUpdateRequest r){service.updateLeg(legId,r);}

    @PostMapping("/legs/{legId}/milestones") public void milestone(@PathVariable UUID legId,@Valid @RequestBody LegMilestoneRequest r){service.addLegMilestone(legId,r);}
    @PostMapping("/legs/{legId}/documents") public void document(@PathVariable UUID legId,@Valid @RequestBody LegDocumentRequest r){service.addLegDocument(legId,r);}
    @PostMapping("/legs/{legId}/costs") public void cost(@PathVariable UUID legId,@Valid @RequestBody LegCostRequest r){service.addLegCost(legId,r);}

    @PostMapping("/integrations") public IntegrationResponse integration(@Valid @RequestBody IntegrationRequest r){return service.registerIntegration(r);}
    @GetMapping("/integrations") public List<IntegrationResponse> integrations(){return service.integrations();}
    @PostMapping("/integrations/{code}/attempt") public void integrationAttempt(@PathVariable String code,@RequestParam String operation,@RequestParam(required=false) String idempotencyKey,@RequestParam String status,@RequestParam(required=false) Integer responseCode,@RequestParam(required=false) String error){service.recordIntegrationAttempt(code,operation,idempotencyKey,status,responseCode,error);}

    @PostMapping("/tracking/{shipmentId}/rotate") public Map<String,Object> rotateTracking(@PathVariable UUID shipmentId,@RequestParam(required=false) String expiresAt){return service.rotateTrackingToken(shipmentId,expiresAt==null?null:java.time.Instant.parse(expiresAt));}
    @PostMapping("/tracking/{shipmentId}/revoke") public void revokeTracking(@PathVariable UUID shipmentId){service.revokeTrackingToken(shipmentId);}

    @PostMapping("/notifications/queue") public void queue(@Valid @RequestBody NotificationQueueRequest r){service.queueNotification(r);}
    @PostMapping("/notifications/queue/{id}/retry") public void retryNotification(@PathVariable UUID id){service.retryNotification(id);}
    @GetMapping("/notifications/queue") public List<Map<String,Object>> queue(){return service.queuedNotifications();}
}
