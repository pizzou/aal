package com.logiplatform.service;

import com.logiplatform.model.AwbRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.model.CargoDocument;
import com.logiplatform.model.CustomsDeclaration;
import com.logiplatform.repository.AwbRecordRepository;
import com.logiplatform.repository.CargoDocumentRepository;
import com.logiplatform.repository.CustomsDeclarationRepository;
import com.logiplatform.repository.DocumentTemplateRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import com.logiplatform.service.control.ReconciliationTaskService;
import com.logiplatform.integration.control.ExternalOperationException;
import com.logiplatform.integration.AirCargoProviderRegistry;
import com.logiplatform.service.AirlineIntegrationAttemptService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.logiplatform.dto.AirCargoDtos.AwbRequest;
import static com.logiplatform.dto.AirCargoDtos.AwbResponse;
import static com.logiplatform.dto.AirCargoDtos.CustomsRequest;
import static com.logiplatform.dto.AirCargoDtos.DocumentRequest;

@Service
public class AirCargoDocumentService {

    private static final ObjectMapper AWB_JSON_MAPPER = new ObjectMapper();

    private final AwbRecordRepository awbs;
    private final CargoDocumentRepository docs;
    private final CustomsDeclarationRepository customs;
    private final ExternalGatewayService external;
    private final DocumentTemplateRepository templates;
    private final AirCargoProviderRegistry providers;
    private final AirlineIntegrationAttemptService integrationAttempts;
    private final ShipmentRepository shipments;
    private final ReconciliationTaskService reconciliation;

    public AirCargoDocumentService(
            AwbRecordRepository awbs,
            CargoDocumentRepository docs,
            CustomsDeclarationRepository customs,
            ExternalGatewayService external,
            DocumentTemplateRepository templates,
            AirCargoProviderRegistry providers,
            AirlineIntegrationAttemptService integrationAttempts,
            ShipmentRepository shipments, ReconciliationTaskService reconciliation) {
        this.awbs = awbs;
        this.docs = docs;
        this.customs = customs;
        this.external = external;
        this.templates = templates;
        this.providers = providers;
        this.integrationAttempts = integrationAttempts;
        this.shipments = shipments;
        this.reconciliation = reconciliation;
    }

    @Transactional
    public AwbResponse createAwb(AwbRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        shipments.findByIdAndTenantId(request.shipmentId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Shipment not found"));
        if ("HAWB".equalsIgnoreCase(request.awbType()) || (request.hawbNumber() != null && !request.hawbNumber().isBlank())) {
            if (request.mawbNumber() == null || request.mawbNumber().isBlank()) {
                throw new IllegalArgumentException("A HAWB requires its carrier-assigned parent MAWB number");
            }
            awbs.findByTenantIdAndAwbNumber(tenantId, request.mawbNumber().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Parent MAWB does not exist for this HAWB"));
        }
        validateJsonArray(request.routingSegmentsJson(), "routingSegmentsJson");
        validateJsonArray(request.cargoRatingLinesJson(), "cargoRatingLinesJson");
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
        awb.applyExtendedDetails(
                request.airlinePrefix(), request.airlineSerial(),
                request.shipperStreetAddress(), request.shipperPostalCode(), request.shipperContactName(), request.shipperPhone(), request.shipperEmail(), request.shipperTaxId(), request.shipperEoriNumber(),
                request.consigneeStreetAddress(), request.consigneePostalCode(), request.consigneeContactName(), request.consigneePhone(), request.consigneeEmail(), request.consigneeTaxId(), request.consigneeEoriNumber(),
                request.issuingAgentCity(), request.iataCargoAgentCode(), request.agentAccountNumber(), request.firstToAirport(), request.firstByCarrier(), request.secondToAirport(), request.secondByCarrier(),
                request.currencyCode(), request.paymentTermsCode(), request.rateClass(), request.weightUnit(), request.ratePerKg(), request.freightCharge(),
                request.lengthCm(), request.widthCm(), request.heightCm(), request.natureQuantityGoods(), request.declaredValueCarriage(), request.carriageValueCode(),
                request.declaredValueCustoms(), request.customsValueCode(), request.insuranceAmount(), request.routingSegmentsJson(), request.cargoRatingLinesJson());
        awb.validateRecord();
        return AwbResponse.from(awbs.save(awb));
    }

    private static void validateJsonArray(String value, String fieldName) {
        if (value == null || value.isBlank()) return;
        try {
            JsonNode node = AWB_JSON_MAPPER.readTree(value);
            if (node == null || !node.isArray()) {
                throw new IllegalArgumentException(fieldName + " must be a JSON array");
            }
            for (JsonNode item : node) {
                if (!item.isObject()) throw new IllegalArgumentException(fieldName + " entries must be JSON objects");
                if ("routingSegmentsJson".equals(fieldName)) {
                    String to = item.path("to").asText("").trim().toUpperCase(java.util.Locale.ROOT);
                    String by = item.path("by").asText("").trim().toUpperCase(java.util.Locale.ROOT);
                    if (!to.matches("[A-Z]{3}") || !by.matches("[A-Z0-9]{2}")) {
                        throw new IllegalArgumentException("Each routing segment requires a 3-letter 'to' airport and a 2-character 'by' carrier code");
                    }
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(fieldName + " contains invalid JSON", ex);
        }
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
        UUID tenant = TenantContext.getTenantId();
        List<CargoDocument> previous = docs.findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(tenant, request.shipmentId());
        CargoDocument prior = previous.stream().filter(d -> d.getDocumentType().equalsIgnoreCase(request.documentType())).findFirst().orElse(null);
        int version = prior == null ? 1 : prior.getVersionNo() + 1;
        CargoDocument document = new CargoDocument(tenant, request.shipmentId(), request.documentType(), request.templateCode(), request.fileUri(), request.contentHash());
        UUID user = null; try { Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal(); if (p instanceof com.logiplatform.security.TenantPrincipal tp) user = tp.userId(); } catch (Exception ignored) {}
        document.setLifecycle(version, prior == null ? null : prior.getId(), user, false, null, null, request.contentHash());
        CargoDocument saved = docs.save(document);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", saved.getId());
        response.put("documentType", saved.getDocumentType());
        response.put("templateCode", saved.getTemplateCode());
        response.put("fileUri", saved.getFileUri());
        response.put("contentHash", saved.getContentHash());
        response.put("status", saved.getStatus());
        response.put("versionNo", saved.getVersionNo());
        response.put("supersedesDocumentId", saved.getSupersedesDocumentId());
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

        var provider = providers.active();
        if (!provider.capabilities().awbSubmission()) {
            throw new IllegalStateException("The configured airline provider does not support AWB submission");
        }
        String key = "AWB-" + awb.getId();
        UUID attempt = integrationAttempts.start(provider.providerCode(), "AWB_SUBMIT", key, UUID.randomUUID().toString(), payload.toString());
        try {
            var result = provider.submitAwb(payload, key);
            integrationAttempts.success(attempt, 200, result.rawResponse());
            awb.submitted(result.providerReference() == null ? awb.getAwbNumber() : result.providerReference());
            return AwbResponse.from(awbs.save(awb));
        } catch (RuntimeException ex) {
            integrationAttempts.failure(attempt, null, ex.getMessage());
            if (ex instanceof ExternalOperationException e && e.outcomeUnknown()) {
                reconciliation.enqueue(provider.providerCode(), "AWB_SUBMIT", "AWB", awb.getId(), key, null, ex.getMessage());
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.ACCEPTED, "AWB submission outcome is unknown and has been queued for reconciliation");
            }
            throw ex;
        }
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

        Map<String, Object> response;
        try {
            response = external.submitCustoms(payload);
        } catch (RuntimeException ex) {
            reconciliation.enqueue("CUSTOMS", "CUSTOMS_SUBMIT", "CUSTOMS_DECLARATION", declaration.getId(), "CUSTOMS-" + declaration.getId(), null, ex.getMessage());
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.ACCEPTED, "Customs submission outcome is unknown and has been queued for reconciliation");
        }
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
