package com.logiplatform.service;

import com.logiplatform.dto.AdvancedEnterpriseDtos.DangerousGoodsRequest;
import com.logiplatform.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class DangerousGoodsService {
    private final JdbcTemplate db;

    public DangerousGoodsService(@Qualifier("tenantJdbcTemplate") JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    public Map<String,Object> declare(DangerousGoodsRequest request) {
        UUID tenant = TenantContext.getTenantId();
        ensureShipment(request.shipmentId());
        validate(request);
        UUID id = UUID.randomUUID();
        db.update("""
            INSERT INTO dangerous_goods_declarations(
                id,tenant_id,shipment_id,cargo_item_id,un_number,proper_shipping_name,hazard_class,packing_group,
                quantity,quantity_unit,package_count,tunnel_code,marine_pollutant,limited_quantity,excepted_quantity,status)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'VALIDATED')
            """, id, tenant, request.shipmentId(), request.cargoItemId(), request.unNumber().trim().toUpperCase(Locale.ROOT),
                request.properShippingName().trim(), request.hazardClass().trim(), nullable(request.packingGroup()), request.quantity(),
                request.quantityUnit().trim(), request.packageCount(), nullable(request.tunnelCode()), request.marinePollutant(),
                request.limitedQuantity(), request.exceptedQuantity());
        db.update("UPDATE dangerous_goods_declarations SET validated_at=now(),validated_by=?,validation_message=? WHERE id=? AND tenant_id=?",
                currentUser(), "Validation passed by deterministic AAL DG controls", id, tenant);
        db.update("UPDATE cargo_items SET dangerous_goods=true,dg_class=?,un_number=? WHERE id=? AND tenant_id=?",
                request.hazardClass().trim(), request.unNumber().trim().toUpperCase(Locale.ROOT), request.cargoItemId(), tenant);
        return one("SELECT * FROM dangerous_goods_declarations WHERE id=? AND tenant_id=?", id, tenant);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list(UUID shipmentId) {
        ensureShipment(shipmentId);
        return db.queryForList("SELECT * FROM dangerous_goods_declarations WHERE tenant_id=? AND shipment_id=? ORDER BY created_at DESC",
                TenantContext.getTenantId(), shipmentId);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID declarationId) {
        Map<String,Object> row = one("SELECT * FROM dangerous_goods_declarations WHERE id=? AND tenant_id=?", declarationId, TenantContext.getTenantId());
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16);
                cs.newLineAtOffset(50, 780);
                cs.showText("AAL DANGEROUS GOODS DECLARATION");
                cs.setFont(PDType1Font.HELVETICA, 10);
                int y = 750;
                for (Map.Entry<String,Object> e : row.entrySet()) {
                    if ("tenant_id".equals(e.getKey()) || "created_at".equals(e.getKey()) || "updated_at".equals(e.getKey())) continue;
                    cs.newLineAtOffset(0, -18);
                    cs.showText(e.getKey() + ": " + safePdf(String.valueOf(e.getValue())));
                    y -= 18;
                    if (y < 80) break;
                }
                cs.endText();
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate DG declaration PDF", e);
        }
    }

    private void validate(DangerousGoodsRequest r) {
        String clazz = r.hazardClass().trim();
        if (!clazz.matches("[1-9](\\.[1-6])?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dangerous-goods hazard class");
        }
        if (r.packageCount() < 1 || r.quantity().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DG quantity/package count must be positive");
        }
        String pg = r.packingGroup() == null ? null : r.packingGroup().trim().toUpperCase(Locale.ROOT);
        if (pg != null && !Set.of("I", "II", "III").contains(pg)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Packing group must be I, II or III");
        }
        if (Set.of("2", "6.2", "7").contains(clazz) && pg != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Packing group is not applicable for hazard class " + clazz);
        }
        if ("1".equals(clazz) && r.unNumber().equalsIgnoreCase("UN0000")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UN number");
        }
    }

    private void ensureShipment(UUID shipmentId) {
        Integer count = db.queryForObject("SELECT count(*) FROM shipments WHERE id=? AND tenant_id=?", Integer.class, shipmentId, TenantContext.getTenantId());
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
    }

    private Map<String,Object> one(String sql, Object... args) { return db.queryForMap(sql,args); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safePdf(String value) { return value == null ? "" : value.replace("\r", " ").replace("\n", " ").replace("(", "[").replace(")", "]"); }
    private UUID currentUser() {
        try {
            Object principal = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof com.logiplatform.security.TenantPrincipal tp) return tp.userId();
        } catch (Exception ignored) {}
        return null;
    }
}
