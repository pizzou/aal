package com.logiplatform.service;

import com.logiplatform.model.Shipment;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.AwbRecordRepository;
import com.logiplatform.model.AwbRecord;


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
 * If a structured AwbRecord exists for the shipment, this service renders the
 * expanded two-page form using its party, routing, rating and valuation fields.
 * The legacy one-page layout remains as a compatibility fallback for shipments
 * without a structured AWB record and explicitly warns that party data is incomplete.
 */
@Service
public class AwbDocumentService {

    private static final float MM = 2.834645f; // points per millimeter
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final ShipmentRepository shipmentRepository;
    private final AwbRecordRepository awbRecordRepository;

    public AwbDocumentService(ShipmentRepository shipmentRepository, AwbRecordRepository awbRecordRepository) {
        this.shipmentRepository = shipmentRepository;
        this.awbRecordRepository = awbRecordRepository;
    }

    public byte[] generateAwb(UUID shipmentId) {
        UUID tenantId = TenantContext.getTenantId();
        Shipment shipment = shipmentRepository.findByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));

        AwbRecord structuredAwb = awbRecordRepository
                .findFirstByTenantIdAndShipmentIdOrderByCreatedAtDesc(tenantId, shipmentId).orElse(null);
        if (structuredAwb != null) {
            return generateStructuredAwb(shipment, structuredAwb);
        }

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

    /** Render the structured AWB record over two pages so legal, routing, rating and valuation data are not silently omitted. */
    private byte[] generateStructuredAwb(Shipment shipment, AwbRecord a) {
        try (PDDocument document = new PDDocument()) {
            float margin = 28f;
            float pageWidth = PDRectangle.A4.getWidth();
            float colW = (pageWidth - 2 * margin - 16f) / 2f;
            float rightX = margin + colW + 16f;
            PDPage first = new PDPage(PDRectangle.A4); document.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(document, first)) {
                float y = 800f;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 15, margin, y, "AIR WAYBILL / AAL CARGO DOCUMENT"); y -= 22;
                String prefix = a.getAirlinePrefix() == null ? "___" : a.getAirlinePrefix();
                String serial = a.getAirlineSerial() == null ? "________" : a.getAirlineSerial();
                drawBox(cs, "AIRLINE PREFIX", prefix, margin, y, 70); drawBox(cs, "8-DIGIT SERIAL", serial, margin + 78, y, 110);
                drawBox(cs, "AWB / HAWB NUMBER", safe(a.getAwbNumber()), margin + 196, y, pageWidth - margin - (margin + 196)); y -= 48;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, y, "SHIPPER / CONSIGNOR");
                drawText(cs, PDType1Font.HELVETICA_BOLD, 10, rightX, y, "CONSIGNEE / IMPORTER"); y -= 18;
                y = drawStack(cs, margin, y, colW, new String[][]{
                    {"Legal name", a.getShipperName()}, {"Street address", first(a.getShipperStreetAddress(), a.getShipperAddress())}, {"Postal code", a.getShipperPostalCode()},
                    {"Contact person", a.getShipperContactName()}, {"Phone", a.getShipperPhone()}, {"Email", a.getShipperEmail()}, {"Tax ID", a.getShipperTaxId()}, {"EORI", a.getShipperEoriNumber()}
                });
                drawStack(cs, rightX, 764f, colW, new String[][]{
                    {"Legal name", a.getConsigneeName()}, {"Street address", first(a.getConsigneeStreetAddress(), a.getConsigneeAddress())}, {"Postal code", a.getConsigneePostalCode()},
                    {"Contact person", a.getConsigneeContactName()}, {"Phone", a.getConsigneePhone()}, {"Email", a.getConsigneeEmail()}, {"Tax ID", a.getConsigneeTaxId()}, {"EORI", a.getConsigneeEoriNumber()}
                });
                y = Math.min(y, 560f) - 4;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, y, "ISSUING CARRIER / AGENT"); y -= 18;
                drawBox(cs, "Agent name", safe(a.getIssuingAgent()), margin, y, colW);
                drawBox(cs, "Agent city", safe(a.getIssuingAgentCity()), rightX, y, colW); y -= 43;
                drawBox(cs, "IATA cargo agent code", safe(a.getIataCargoAgentCode()), margin, y, colW);
                drawBox(cs, "Agent airline account number", safe(a.getAgentAccountNumber()), rightX, y, colW); y -= 44;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, y, "ROUTING / DESTINATION MATRIX"); y -= 17;
                drawBox(cs, "Origin (IATA)", safe(a.getOriginAirport()), margin, y, 100);
                drawBox(cs, "To / first airport", safe(a.getFirstToAirport()), margin + 108, y, 100);
                drawBox(cs, "By / carrier", safe(a.getFirstByCarrier()), margin + 216, y, 75);
                drawBox(cs, "To / second airport", safe(a.getSecondToAirport()), margin + 299, y, 100);
                drawBox(cs, "By / carrier", safe(a.getSecondByCarrier()), margin + 407, y, 75); y -= 43;
                drawBox(cs, "Destination (IATA)", safe(a.getDestinationAirport()), margin, y, 130);
                drawBox(cs, "Currency", safe(a.getCurrencyCode()), margin + 140, y, 90);
                drawBox(cs, "Freight payment", safe(a.getPaymentTermsCode()), margin + 240, y, 110);
                drawBox(cs, "Carrier", safe(shipment.getCarrierName()), margin + 360, y, pageWidth - margin - (margin + 360)); y -= 43;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 8, margin, y, "ADDITIONAL ROUTING SEGMENTS (JSON ARRAY)"); y -= 13;
                drawParagraph(cs, margin, y, pageWidth - 2 * margin, safe(a.getRoutingSegmentsJson()));
            }
            PDPage second = new PDPage(PDRectangle.A4); document.addPage(second);
            try (PDPageContentStream cs = new PDPageContentStream(document, second)) {
                float y = 800f;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 15, margin, y, "CARGO RATING / VALUATION DETAILS"); y -= 30;
                drawBox(cs, "Number of pieces", safe(a.getPieces()), margin, y, 115);
                drawBox(cs, "Gross weight (kg)", safe(a.getGrossWeightKg()), margin + 125, y, 120);
                drawBox(cs, "Chargeable weight (kg)", safe(a.getChargeableWeightKg()), margin + 255, y, 140);
                drawBox(cs, "Weight unit (K/L)", safe(a.getWeightUnit()), margin + 405, y, pageWidth - margin - (margin + 405)); y -= 44;
                drawBox(cs, "Rate class", safe(a.getRateClass()), margin, y, 100);
                drawBox(cs, "Rate per kg", safe(a.getRatePerKg()), margin + 110, y, 120);
                drawBox(cs, "Freight charge", safe(a.getFreightCharge()), margin + 240, y, 140);
                drawBox(cs, "Currency", safe(a.getCurrencyCode()), margin + 390, y, 90); y -= 44;
                drawBox(cs, "Length (cm)", safe(a.getLengthCm()), margin, y, 110);
                drawBox(cs, "Width (cm)", safe(a.getWidthCm()), margin + 120, y, 110);
                drawBox(cs, "Height (cm)", safe(a.getHeightCm()), margin + 240, y, 110);
                drawBox(cs, "Volumetric weight (kg)", safe(a.getVolumetricWeightKg()), margin + 360, y, pageWidth - margin - (margin + 360)); y -= 50;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 9, margin, y, "NATURE AND QUANTITY OF GOODS / HS CODE"); y -= 15;
                drawParagraph(cs, margin, y, pageWidth - 2 * margin, first(a.getNatureQuantityGoods(), a.getCommodity()) + " | HS Code: " + safe(a.getHsCode())); y -= 43;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 8, margin, y, "ADDITIONAL CARGO RATING LINES (JSON ARRAY)"); y -= 13;
                drawParagraph(cs, margin, y, pageWidth - 2 * margin, safe(a.getCargoRatingLinesJson())); y -= 42;
                drawText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, y, "VALUATION / CUSTOMS DECLARATIONS"); y -= 20;
                drawBox(cs, "Declared value for carriage", safe(a.getDeclaredValueCarriage()), margin, y, 150);
                drawBox(cs, "Carriage value code (NVD)", safe(a.getCarriageValueCode()), margin + 160, y, 140);
                drawBox(cs, "Declared value for customs", safe(a.getDeclaredValueCustoms()), margin + 310, y, 150); y -= 44;
                drawBox(cs, "Customs value code (NCV)", safe(a.getCustomsValueCode()), margin, y, 180);
                drawBox(cs, "Insurance amount / premium", safe(a.getInsuranceAmount()), margin + 190, y, 190);
                drawBox(cs, "Dangerous goods", Boolean.TRUE.equals(a.getDangerousGoods()) ? "YES - see declaration" : "NO", margin + 390, y, 90); y -= 54;
                drawText(cs, PDType1Font.HELVETICA, 8, margin, y, "Routing segments and cargo rating lines may be supplied as structured JSON and are retained with this AWB record."); y -= 35;
                drawText(cs, PDType1Font.HELVETICA, 8, margin, y, "Shipper / agent signature: __________________________");
                drawText(cs, PDType1Font.HELVETICA, 8, rightX, y, "Issuing carrier signature: __________________________"); y -= 25;
                drawText(cs, PDType1Font.HELVETICA_OBLIQUE, 7, margin, y, "Generated from AAL system records. Carrier-issued MAWB numbers and agent accreditation must be verified with the issuing carrier/IATA.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); document.save(out); return out.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate structured AWB document");
        }
    }

    private float drawStack(PDPageContentStream cs, float x, float y, float w, String[][] fields) throws IOException {
        for (String[] field : fields) { drawBox(cs, field[0], safe(field[1]), x, y, w); y -= 30; }
        return y;
    }
    private void drawBox(PDPageContentStream cs, String label, String value, float x, float y, float w) throws IOException {
        drawText(cs, PDType1Font.HELVETICA, 6.5f, x, y, label);
        cs.addRect(x, y - 14, w, 11); cs.stroke();
        String v = safe(value); if (v.length() > Math.max(8, (int)(w / 4.2f))) v = v.substring(0, Math.max(5, (int)(w / 4.2f) - 1)) + "...";
        drawText(cs, PDType1Font.HELVETICA_BOLD, 7.5f, x + 2, y - 11, v);
    }
    private void drawParagraph(PDPageContentStream cs, float x, float y, float w, String value) throws IOException {
        cs.addRect(x, y - 30, w, 28); cs.stroke();
        String v = safe(value); if (v.length() > 150) v = v.substring(0, 149) + "...";
        drawText(cs, PDType1Font.HELVETICA, 8, x + 4, y - 14, v);
    }
    private static String first(String preferred, String fallback) { return preferred == null || preferred.isBlank() ? safe(fallback) : preferred; }
    private static String safe(Object value) {
        if (value == null) return "-";
        String s = String.valueOf(value).replaceAll("[^\\x20-\\x7E]", "?").replace('\r', ' ').replace('\n', ' ').trim();
        return s.isBlank() ? "-" : s;
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
