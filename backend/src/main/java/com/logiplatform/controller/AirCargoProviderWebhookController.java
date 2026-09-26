package com.logiplatform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.service.ShipmentEtaTrackingService;
import com.logiplatform.tenancy.TenantContext;
import com.logiplatform.repository.AwbRecordRepository;
import com.logiplatform.repository.AirCargoBookingRepository;
import com.logiplatform.service.AirlineDeadLetterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/public/integrations/air-cargo/webhooks")
public class AirCargoProviderWebhookController {
    private final ObjectMapper mapper; private final AirCargoProviderRegistry providers; private final ShipmentEtaTrackingService eta; private final AwbRecordRepository awbs; private final AirCargoBookingRepository bookings; private final AirlineDeadLetterService deadLetters; private final UUID tenantId; private final String secret;
    public AirCargoProviderWebhookController(ObjectMapper mapper,AirCargoProviderRegistry providers,ShipmentEtaTrackingService eta,AwbRecordRepository awbs,AirCargoBookingRepository bookings,AirlineDeadLetterService deadLetters,@Value("${app.single-tenant.id}") String tenant,@Value("${aircargo.webhook.secret:}") String secret){this.mapper=mapper;this.providers=providers;this.eta=eta;this.awbs=awbs;this.bookings=bookings;this.deadLetters=deadLetters;this.tenantId=UUID.fromString(tenant);this.secret=secret==null?"":secret;}
    @PostMapping("/{provider}") public ResponseEntity<Map<String,Object>> receive(@PathVariable String provider,@RequestHeader(value="X-AAL-Signature",required=false) String signature,@RequestBody String body){
        if(secret.isBlank()||!constantTimeEquals(normalizeSignature(signature),sign(body,secret)))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("accepted",false,"error","Invalid webhook signature"));
        TenantContext.setTenantId(tenantId);
        try{JsonNode n=mapper.readTree(body);String shipmentId=n.path("shipmentId").asText(null);
            String eventType=n.path("eventType").asText("").toUpperCase(Locale.ROOT);
            if (eventType.startsWith("BOOKING_")) {
                String providerReference=n.path("providerReference").asText(null);
                var booking=providerReference==null?null:bookings.findByTenantIdAndProviderReference(tenantId,providerReference).orElse(null);
                if(booking==null)return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("accepted",false,"error","Booking not found"));
                if("BOOKING_CONFIRMED".equals(eventType)) booking.confirm(n.has("confirmedWeightKg")?n.get("confirmedWeightKg").decimalValue():booking.getRequestedWeightKg(),providerReference,n.path("confirmationNumber").asText(providerReference),body);
                else if("BOOKING_CANCELLED".equals(eventType)) booking.cancel("PROVIDER_WEBHOOK",body);
                else booking.fail(body);
                bookings.save(booking);
                return ResponseEntity.accepted().body(Map.of("accepted",true,"type","BOOKING"));
            }
            if (eventType.startsWith("AWB_")) {
                String awbNumber=n.path("awbNumber").asText(null);
                String carrierReference=n.path("carrierReference").asText(null);
                var awb=(awbNumber!=null?awbs.findByTenantIdAndAwbNumber(tenantId,awbNumber):Optional.<com.logiplatform.model.AwbRecord>empty())
                        .orElseGet(()->carrierReference==null?null:awbs.findByTenantIdAndCarrierReference(tenantId,carrierReference).orElse(null));
                if(awb==null)return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("accepted",false,"error","AWB not found"));
                awb.acceptance("AWB_ACCEPTED".equals(eventType)?"ACCEPTED":"REJECTED",carrierReference,n.path("oneRecordReference").asText(null));
                awbs.save(awb);
                return ResponseEntity.accepted().body(Map.of("accepted",true,"type","AWB"));
            }
            String flightStatus=n.path("flightStatus").asText(n.path("status").asText("UNKNOWN"));
            var status=new com.logiplatform.integration.AirCargoProviderPort.FlightStatus(flightStatus,instant(n,"scheduledDeparture"),instant(n,"estimatedDeparture"),instant(n,"actualDeparture"),instant(n,"scheduledArrival"),instant(n,"estimatedArrival"),instant(n,"actualArrival"),n.path("departureDelayMinutes").asInt(0),n.path("arrivalDelayMinutes").asInt(0),n.path("providerEventId").asText(null),body);
            if(shipmentId==null)return ResponseEntity.badRequest().body(Map.of("accepted",false,"error","shipmentId is required for flight events"));eta.apply(UUID.fromString(shipmentId),status,provider.toUpperCase(Locale.ROOT));return ResponseEntity.accepted().body(Map.of("accepted",true));
        }catch(Exception e){try{deadLetters.enqueue(provider.toUpperCase(Locale.ROOT),"WEBHOOK",null,UUID.randomUUID().toString(),body,e.getMessage());}catch(Exception ignored){}return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("accepted",false,"error","Webhook payload could not be processed"));}finally{TenantContext.clear();}
    }
    private static Instant instant(JsonNode n,String key){String s=n.path(key).asText(null);if(s==null||s.isBlank())return null;try{return Instant.parse(s);}catch(Exception e){return null;}}
    private static String sign(String body,String secret){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[] d=mac.doFinal(body.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String normalizeSignature(String signature){if(signature==null)return null;String s=signature.trim();if(s.regionMatches(true,0,"sha256=",0,7))return s.substring(7);return s;}
    private static boolean constantTimeEquals(String a,String b){if(a==null||b==null)return false;return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
}
