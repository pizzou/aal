package com.logiplatform.controller;

import com.logiplatform.dto.AdvancedEnterpriseDtos.*;
import com.logiplatform.service.*;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/enterprise-advanced")
public class AdvancedEnterpriseController {
    private final CommercialPricingAdminService pricing;
    private final DangerousGoodsService dg;
    private final FreightAuditService freightAudit;
    private final SmsNotificationService sms;

    public AdvancedEnterpriseController(CommercialPricingAdminService pricing, DangerousGoodsService dg,
                                         FreightAuditService freightAudit, SmsNotificationService sms) {
        this.pricing=pricing; this.dg=dg; this.freightAudit=freightAudit; this.sms=sms;
    }

    @PostMapping("/pricing/discounts")
    public Map<String,Object> discount(@Valid @RequestBody PricingDiscountRequest request){return pricing.discount(request);}
    @GetMapping("/pricing/discounts")
    public List<Map<String,Object>> discounts(){return pricing.discounts();}
    @PostMapping("/pricing/margin-controls")
    public Map<String,Object> margin(@Valid @RequestBody MarginControlRequest request){return pricing.marginControl(request);}
    @GetMapping("/pricing/margin-controls")
    public List<Map<String,Object>> marginControls(){return pricing.marginControls();}

    @PostMapping("/shipments/{shipmentId}/dangerous-goods")
    public Map<String,Object> dg(@PathVariable UUID shipmentId,@Valid @RequestBody DangerousGoodsRequest request){
        if(!shipmentId.equals(request.shipmentId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Shipment ID mismatch");
        return dg.declare(request);
    }
    @GetMapping("/shipments/{shipmentId}/dangerous-goods")
    public List<Map<String,Object>> dgList(@PathVariable UUID shipmentId){return dg.list(shipmentId);}
    @GetMapping("/dangerous-goods/{id}/pdf")
    public ResponseEntity<ByteArrayResource> dgPdf(@PathVariable UUID id){
        byte[] bytes=dg.pdf(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=aal-dg-declaration-"+id+".pdf").body(new ByteArrayResource(bytes));
    }

    @PostMapping("/shipments/{shipmentId}/freight-audit")
    public Map<String,Object> audit(@PathVariable UUID shipmentId){return freightAudit.audit(shipmentId);}
    @GetMapping("/shipments/{shipmentId}/freight-audit")
    public Map<String,Object> latestAudit(@PathVariable UUID shipmentId){return freightAudit.latest(shipmentId);}
    @GetMapping("/freight-audit")
    public List<Map<String,Object>> auditQueue(@RequestParam(required=false) String status){return freightAudit.queue(status);}

    @PostMapping("/sms")
    public Map<String,Object> sms(@Valid @RequestBody SmsRequest request){return sms.send(request.shipmentId(),request.recipient(),request.message(),request.idempotencyKey());}
}
