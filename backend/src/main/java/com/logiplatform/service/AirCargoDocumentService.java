package com.logiplatform.service;

import com.logiplatform.model.AwbRecord;
import com.logiplatform.model.CargoDocument;
import com.logiplatform.model.CustomsDeclaration;
import com.logiplatform.repository.AwbRecordRepository;
import com.logiplatform.repository.CargoDocumentRepository;
import com.logiplatform.repository.CustomsDeclarationRepository;
import com.logiplatform.repository.DocumentTemplateRepository;
import com.logiplatform.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.logiplatform.dto.AirCargoDtos.AwbRequest;
import static com.logiplatform.dto.AirCargoDtos.AwbResponse;
import static com.logiplatform.dto.AirCargoDtos.CustomsRequest;
import static com.logiplatform.dto.AirCargoDtos.DocumentRequest;

@Service
public class AirCargoDocumentService {

    private final AwbRecordRepository awbs;
    private final CargoDocumentRepository docs;
    private final CustomsDeclarationRepository customs;
    private final ExternalGatewayService external;
    private final DocumentTemplateRepository templates;

    public AirCargoDocumentService(
            AwbRecordRepository awbs,
            CargoDocumentRepository docs,
            CustomsDeclarationRepository customs,
            ExternalGatewayService external,
            DocumentTemplateRepository templates) {
        this.awbs = awbs;
        this.docs = docs;
        this.customs = customs;
        this.external = external;
        this.templates = templates;
    }

    @Transactional
    public AwbResponse createAwb(AwbRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        AwbRecord awb = new AwbRecord(
                tenantId,
                request.shipmentId(),
                request.awbNumber(),
                request.awbType(),
                request.mawbNumber(),
                request.hawbNumber(),
                request.shipperName(),
                request.shipperAddress(),
                request.consigneeName(),
                request.consigneeAddress(),
                request.issuingAgent(),
                request.originAirport(),
                request.destinationAirport(),
                request.pieces(),
                request.grossWeightKg(),
                request.chargeableWeightKg(),
                request.commodity(),
                request.hsCode(),
                request.specialHandling(),
                request.dangerousGoods());
        awb.validateRecord();
        return AwbResponse.from(awbs.save(awb));
    }

    @Transactional
    public AwbResponse submitAwb(UUID id, String carrierReference) {
        AwbRecord awb = awbs.findByTenantIdAndId(TenantContext.getTenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("AWB not found"));
        String reference = carrierReference == null ? "" : carrierReference.trim();
        if (reference.isBlank()) {
            throw new IllegalArgumentException("carrierReference is required");
        }
        awb.submitted(reference);
        return AwbResponse.from(awbs.save(awb));
    }

    @Transactional
    public Map<String, Object> createCustoms(CustomsRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        CustomsDeclaration declaration = customs.save(new CustomsDeclaration(
                tenantId,
                request.shipmentId(),
                request.declarationType(),
                request.customsAuthority(),
                request.brokerName(),
                request.hsCodes(),
                request.countryOfOrigin(),
                request.declaredValue(),
                request.currency()));
        return map(declaration);
    }

    @Transactional
    public Map<String, Object> submitCustoms(UUID id) {
        CustomsDeclaration declaration = customs.findByTenantIdAndId(TenantContext.getTenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("Customs declaration not found"));
        declaration.readyForSubmission();
        return map(customs.save(declaration));
    }

    @Transactional
    public Map<String, Object> createDocument(DocumentRequest request) {
        CargoDocument document = docs.save(new CargoDocument(
                TenantContext.getTenantId(),
                request.shipmentId(),
                request.documentType(),
                request.templateCode(),
                request.fileUri(),
                request.contentHash()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", document.getId());
        response.put("documentType", document.getDocumentType());
        response.put("templateCode", document.getTemplateCode());
        response.put("fileUri", document.getFileUri());
        response.put("contentHash", document.getContentHash());
        response.put("status", document.getStatus());
        return response;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> documentsForShipment(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        awbs.findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(tenantId, shipmentId).forEach(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", "AWB");
            m.put("id", a.getId());
            m.put("number", a.getAwbNumber());
            m.put("status", a.getSubmissionStatus());
            m.put("validationStatus", a.getValidationStatus());
            m.put("createdAt", a.getCreatedAt());
            result.add(m);
        });
        customs.findAllByTenantIdAndShipmentIdOrderBySubmittedAtDesc(tenantId, shipmentId).forEach(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", "CUSTOMS");
            m.put("id", c.getId());
            m.put("number", c.getExternalReference());
            m.put("status", c.getStatus());
            m.put("documentType", c.getDeclarationType());
            result.add(m);
        });
        docs.findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(tenantId, shipmentId).forEach(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", "DOCUMENT");
            m.put("id", d.getId());
            m.put("number", d.getTemplateCode());
            m.put("status", d.getStatus());
            m.put("documentType", d.getDocumentType());
            m.put("fileUri", d.getFileUri());
            m.put("createdAt", d.getCreatedAt());
            result.add(m);
        });
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> templates() {
        UUID tenantId = TenantContext.getTenantId();
        var existing = templates.findAllByTenantIdAndActiveTrueOrderByTemplateCodeAsc(tenantId);
        if (!existing.isEmpty()) {
            return existing.stream().map(template -> {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("id", template.getId());
                response.put("templateCode", template.getTemplateCode());
                response.put("name", template.getName());
                response.put("version", template.getVersion());
                response.put("requiredFields", template.getRequiredFields());
                return response;
            }).toList();
        }

        return List.of(
                "FREIGHT_AUDIT",
                "DANGEROUS_GOODS_DECLARATION",
                "SHIPPING_LOG",
                "COMMERCIAL_INVOICE",
                "PACKING_LIST",
                "CERTIFICATE_OF_ORIGIN",
                "CUSTOMS_PACKET")
                .stream()
                .map(code -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("templateCode", code);
                    response.put("status", "STANDARDIZED");
                    return response;
                })
                .toList();
    }

    @Transactional
    public AwbResponse submitAwbToCarrier(UUID id) {
        AwbRecord awb = awbs.findByTenantIdAndId(TenantContext.getTenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("AWB not found"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("awbNumber", awb.getAwbNumber());
        payload.put("awbType", awb.getAwbType());
        payload.put("mawbNumber", awb.getMawbNumber());
        payload.put("hawbNumber", awb.getHawbNumber());
        payload.put("shipmentId", awb.getShipmentId());
        payload.put("shipperName", awb.getShipperName());
        payload.put("consigneeName", awb.getConsigneeName());
        payload.put("originAirport", awb.getOriginAirport());
        payload.put("destinationAirport", awb.getDestinationAirport());
        payload.put("pieces", awb.getPieces());
        payload.put("grossWeightKg", awb.getGrossWeightKg());
        payload.put("chargeableWeightKg", awb.getChargeableWeightKg());
        payload.put("commodity", awb.getCommodity());
        payload.put("idempotencyKey", "AWB-" + awb.getId());

        Map<String, Object> response = external.submitAwb(payload);
        awb.submitted(String.valueOf(response.getOrDefault("reference", awb.getAwbNumber())));
        return AwbResponse.from(awbs.save(awb));
    }

    @Transactional
    public Map<String, Object> submitCustomsToExternal(UUID id) {
        CustomsDeclaration declaration = customs.findByTenantIdAndId(TenantContext.getTenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("Customs declaration not found"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipmentId", declaration.getShipmentId());
        payload.put("declarationType", declaration.getDeclarationType());
        payload.put("customsAuthority", declaration.getCustomsAuthority());
        payload.put("brokerName", declaration.getBrokerName());
        payload.put("hsCodes", declaration.getHsCodes());
        payload.put("countryOfOrigin", declaration.getCountryOfOrigin());
        payload.put("declaredValue", declaration.getDeclaredValue());
        payload.put("currency", declaration.getCurrency());
        payload.put("idempotencyKey", "CUSTOMS-" + declaration.getId());

        Map<String, Object> response = external.submitCustoms(payload);
        declaration.submit(
                String.valueOf(response.getOrDefault("reference", "PENDING-EXTERNAL-ACK")),
                String.valueOf(response.getOrDefault(
                        "message",
                        "Submitted to configured customs/broker endpoint")));
        customs.save(declaration);
        return map(declaration);
    }

    private Map<String, Object> map(CustomsDeclaration declaration) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", declaration.getId());
        response.put("shipmentId", declaration.getShipmentId());
        response.put("declarationType", declaration.getDeclarationType());
        response.put("customsAuthority", declaration.getCustomsAuthority());
        response.put("brokerName", declaration.getBrokerName());
        response.put("hsCodes", declaration.getHsCodes());
        response.put("status", declaration.getStatus());
        response.put("externalReference", declaration.getExternalReference());
        response.put("submittedAt", declaration.getSubmittedAt());
        return response;
    }
}
