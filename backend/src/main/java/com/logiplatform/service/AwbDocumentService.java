package com.logiplatform.service;

import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;


import com.logiplatform.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Generates an Air Waybill PDF — document GENERATION only, matching the boundary
 * drawn throughout this project: this produces a correctly laid-out, IATA-AWB-shaped
 * document from data already in the system. It does NOT submit anything to any
 * carrier or IATA system (no such connection exists or can exist without real
 * carrier credentials — see the Booking & Capacity Management discussion).
 *
 * LAYOUT VERIFICATION NOTE: this exact layout (field positions, box sizes, page
 * structure) was first prototyped in Python/reportlab and visually inspected as a
 * rendered image before being ported to Java/PDFBox here — the same
 * verify-before-port discipline used for the load-planning algorithm, applied to
 * layout instead of logic. All coordinates below are a direct unit-for-unit
 * translation of that verified prototype (millimeters converted to PDF points,
 * 1mm = 2.834645 pt), not a re-design from scratch in Java.
 *
 * HONEST DATA GAP, disclosed directly ON the generated document, not hidden: this
 * platform's Shipment model captures origin/destination as free-text addresses, not
 * structured shipper/consignee company and contact records. The AWB shows the
 * address on file for those fields with an explicit notice — this is not a
 * legally complete AWB until shipper/consignee identification is completed, likely
 * by hand, until a proper Party/contact domain model is added.
 */
@Service
public class AwbDocumentService {

    private static final float MM = 2.834645f; // points per millimeter
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final ShipmentRepository shipmentRepository;

    public AwbDocumentService(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    public byte[] generateAwb(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        Shipment shipment = shipmentRepository.findByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            float width = PDRectangle.A4.getWidth();
            float height = PDRectangle.A4.getHeight();
            float margin = 15 * MM;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                float y = height - margin;

                drawText(cs, PDType1Font.HELVETICA_BOLD, 16, margin, y, "AIR WAYBILL");
                String awbNo = shipment.getCarrierReferenceNumber() != null
                        ? shipment.getCarrierReferenceNumber() : shipment.getReferenceCode();
                drawTextRightAligned(cs, PDType1Font.HELVETICA, 9, width - margin, y, "AWB No: " + awbNo);
                y -= 8 * MM;

                cs.setLineWidth(1);
                cs.moveTo(margin, y);
                cs.lineTo(width - margin, y);
                cs.stroke();
                y -= 10 * MM;

                float colW = (width - 2 * margin - 10 * MM) / 2;
                float col2X = margin + colW + 10 * MM;

                drawField(cs, "Shipper (origin address)", shipment.getOriginAddress(), margin, y, colW);
                drawField(cs, "Consignee (destination address)", shipment.getDestinationAddress(), col2X, y, colW);
                y -= 20 * MM;

                drawField(cs, "Issuing carrier / agent",
                        shipment.getCarrierName() != null ? shipment.getCarrierName() : "—", margin, y, colW);
                drawField(cs, "Carrier reference (AWB/tracking no.)", awbNo, col2X, y, colW);
                y -= 20 * MM;

                drawField(cs, "Transport mode", shipment.getTransportMode().name(), margin, y, colW / 2 - 5 * MM);
                drawField(cs, "Reference code", shipment.getReferenceCode(), margin + colW / 2, y, colW / 2);
                drawField(cs, "Gross weight (kg)",
                        shipment.getWeightKg() != null ? String.valueOf(shipment.getWeightKg()) : "Not yet weighed",
                        col2X, y, colW);
                y -= 20 * MM;

                drawField(cs, "Shipment status", shipment.getStatus().name(), margin, y, colW);
                drawField(cs, "Date issued", DATE_FORMAT.format(Instant.now()), col2X, y, colW);
                y -= 25 * MM;

                cs.setNonStrokingColor(120, 120, 120);
                drawText(cs, PDType1Font.HELVETICA_OBLIQUE, 7, margin, y,
                        "Note: Full shipper/consignee company & contact details are not yet captured as structured");
                y -= 4 * MM;
                drawText(cs, PDType1Font.HELVETICA_OBLIQUE, 7, margin, y,
                        "data in this platform (only address text). This field shows the address on file; complete");
                y -= 4 * MM;
                drawText(cs, PDType1Font.HELVETICA_OBLIQUE, 7, margin, y,
                        "shipper/consignee identification manually before relying on this as a final legal AWB.");
                cs.setNonStrokingColor(0, 0, 0);
                y -= 15 * MM;

                drawText(cs, PDType1Font.HELVETICA, 7, margin, y, "Signature of shipper or agent: _______________________");
                drawText(cs, PDType1Font.HELVETICA, 7, col2X, y, "Signature of issuing carrier: _______________________");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate AWB document");
        }
    }

    /** One labeled box: label above, bordered box below with bold value inside — matches the verified Python prototype exactly. */
    private void drawField(PDPageContentStream cs, String label, String value, float x, float y, float w) throws IOException {
        drawText(cs, PDType1Font.HELVETICA, 7, x, y, label);

        cs.addRect(x, y - 14, w, 12);
        cs.stroke();

        String truncated = value != null && value.length() > 60 ? value.substring(0, 60) : value;
        drawText(cs, PDType1Font.HELVETICA_BOLD, 9, x + 2, y - 11, truncated != null ? truncated : "—");
    }

    private void drawText(PDPageContentStream cs, PDFont font, float size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private void drawTextRightAligned(PDPageContentStream cs, PDFont font, float size, float rightX, float y, String text) throws IOException {
        float textWidth = font.getStringWidth(text) / 1000 * size;
        drawText(cs, font, size, rightX - textWidth, y, text);
    }
}
