package com.logiplatform.service;

import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.model.CommercialPayment;
import com.logiplatform.model.ClientRecord;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.repository.CommercialPaymentRepository;
import com.logiplatform.repository.ClientRecordRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FinancialDocumentService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_DATE;

    private final CommercialInvoiceRepository invoices;
    private final CommercialPaymentRepository payments;
    private final ShipmentRepository shipments;
    private final ClientRecordRepository clients;
    private final JdbcTemplate tenantDb;
    private final MailService mail;
    private final FinancialDocumentArchiveService archive;

    @Value("${app.company.name:Aviation Africa Logistics Ltd}")
    private String companyName;
    @Value("${app.company.address:}")
    private String companyAddress;
    @Value("${app.company.email:}")
    private String companyEmail;
    @Value("${app.company.phone:}")
    private String companyPhone;
    @Value("${app.company.registration-number:}")
    private String companyRegistrationNumber;
    @Value("${app.company.tax-id:}")
    private String companyTaxId;
    @Value("${app.company.website:}")
    private String companyWebsite;
    @Value("${app.company.payment-instructions:}")
    private String paymentInstructions;

    @Autowired
    public FinancialDocumentService(
            CommercialInvoiceRepository invoices,
            CommercialPaymentRepository payments,
            ShipmentRepository shipments,
            ClientRecordRepository clients,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate tenantDb,
            MailService mail,
            FinancialDocumentArchiveService archive) {
        this.invoices = invoices;
        this.payments = payments;
        this.shipments = shipments;
        this.clients = clients;
        this.tenantDb = tenantDb;
        this.mail = mail;
        this.archive = archive;
    }

    /** Compatibility constructor retained for unit tests and older direct callers. */
    public FinancialDocumentService(
            CommercialInvoiceRepository invoices,
            CommercialPaymentRepository payments,
            ShipmentRepository shipments,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate tenantDb,
            MailService mail,
            FinancialDocumentArchiveService archive) {
        this(invoices, payments, shipments, null, tenantDb, mail, archive);
    }

    @Transactional(readOnly = true)
    public byte[] invoicePdf(UUID invoiceId) {
        CommercialInvoice invoice = invoice(invoiceId);
        Shipment shipment = shipment(invoice.getShipmentId());
        ClientRecord client = client(invoice.getClient());
        List<InvoiceLine> lines = lines(invoiceId);
        if (lines.isEmpty()) lines = fallbackLines(invoice, shipment);
        byte[] pdf = buildInvoice(invoice, shipment, client, lines);
        archive.archive("INVOICE", invoice.getId(), "invoice-" + invoice.getInvoiceNo() + ".pdf",
                "application/pdf", pdf);
        return pdf;
    }

    @Transactional(readOnly = true)
    public byte[] receiptPdf(UUID paymentId) {
        CommercialPayment payment = payment(paymentId);
        CommercialInvoice invoice = invoice(payment.getInvoiceId());
        Shipment shipment = shipment(invoice.getShipmentId());
        byte[] pdf = buildReceipt(payment, invoice, shipment);
        archive.archive("RECEIPT", payment.getId(), "receipt-" + receiptNumber(payment) + ".pdf",
                "application/pdf", pdf);
        return pdf;
    }

    @Transactional(readOnly = true)
    public ReceiptReference latestReceipt(UUID invoiceId) {
        CommercialPayment p = payments.findTopByTenantIdAndInvoiceIdOrderByCreatedAtDesc(
                requireTenant(), invoiceId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment receipt exists for this invoice"));
        return new ReceiptReference(p.getId(), receiptNumber(p), p.getAmount(), p.getCurrency(), p.getCreatedAt().toString());
    }

    public void emailInvoice(UUID invoiceId, String recipientEmail, String recipientName) {
        CommercialInvoice invoice = invoice(invoiceId);
        String to = recipient(invoiceId, recipientEmail);
        if (to == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No client email is available. Supply recipientEmail or configure the shipment notification email.");
        mail.sendFinancialDocument(
                to,
                blank(recipientName) ? invoice.getClient() : recipientName,
                "Invoice " + invoice.getInvoiceNo(),
                "Your invoice <strong>" + esc(invoice.getInvoiceNo()) + "</strong> is attached.",
                "invoice-" + invoice.getInvoiceNo() + ".pdf",
                invoicePdf(invoiceId));
    }

    public void emailReceipt(UUID paymentId, String recipientEmail, String recipientName) {
        CommercialPayment payment = payment(paymentId);
        CommercialInvoice invoice = invoice(payment.getInvoiceId());
        String to = recipient(payment.getInvoiceId(), recipientEmail);
        if (to == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No client email is available. Supply recipientEmail or configure the shipment notification email.");
        mail.sendFinancialDocument(
                to,
                blank(recipientName) ? invoice.getClient() : recipientName,
                "Payment receipt " + receiptNumber(payment),
                "Your payment of <strong>" + esc(money(payment.getAmount(), payment.getCurrency()))
                        + "</strong> has been recorded against invoice <strong>"
                        + esc(invoice.getInvoiceNo()) + "</strong>.",
                "receipt-" + receiptNumber(payment) + ".pdf",
                receiptPdf(paymentId));
    }


    public String invoiceSecureLink(UUID invoiceId, java.time.Duration ttl) {
        invoicePdf(invoiceId);
        UUID id=archive.latestArchiveId("INVOICE",invoiceId);
        if(id==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Archived invoice document not found");
        return archive.issueLink(id,ttl);
    }

    public String receiptSecureLink(UUID paymentId, java.time.Duration ttl) {
        receiptPdf(paymentId);
        UUID id=archive.latestArchiveId("RECEIPT",paymentId);
        if(id==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Archived receipt document not found");
        return archive.issueLink(id,ttl);
    }

    private String recipient(UUID invoiceId, String override) {
        if (!blank(override)) return email(override);
        CommercialInvoice invoice = invoice(invoiceId);
        if (invoice.getShipmentId() != null) {
            Shipment shipment = shipment(invoice.getShipmentId());
            if (shipment != null && !blank(shipment.getNotificationEmail()))
                return email(shipment.getNotificationEmail());
        }
        return null;
    }

    private CommercialInvoice invoice(UUID id) {
        return invoices.findByTenantIdAndId(requireTenant(), id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    private CommercialPayment payment(UUID id) {
        return payments.findByTenantIdAndId(requireTenant(), id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    private Shipment shipment(UUID id) {
        if (id == null) return null;
        return shipments.findByIdAndTenantId(id, requireTenant()).orElse(null);
    }

    private List<InvoiceLine> lines(UUID invoiceId) {
        return tenantDb.query("""
            SELECT line_no, charge_code, description, quantity, unit_price, amount, currency
              FROM commercial_invoice_lines
             WHERE tenant_id=? AND invoice_id=?
             ORDER BY line_no
            """,
            (rs, n) -> new InvoiceLine(
                rs.getInt(1), rs.getString(2), rs.getString(3),
                nz(rs.getBigDecimal(4), BigDecimal.ONE),
                nz(rs.getBigDecimal(5), BigDecimal.ZERO),
                nz(rs.getBigDecimal(6), BigDecimal.ZERO),
                rs.getString(7)),
            requireTenant(), invoiceId);
    }

    private ClientRecord client(String clientName) {
        if (clients == null || blank(clientName)) return null;
        return clients.findFirstByTenantIdAndClientCompanyIgnoreCase(requireTenant(), clientName).orElse(null);
    }

    private List<InvoiceLine> fallbackLines(CommercialInvoice invoice, Shipment shipment) {
        String description = "Freight and logistics services";
        if (shipment != null) {
            String route = firstNonBlank(shipment.getOriginCityPort(), shipment.getOriginAddress(), shipment.getOriginCountry())
                    + " to " + firstNonBlank(shipment.getDestinationCityPort(), shipment.getDestinationAddress(), shipment.getDestinationCountry());
            description += " - " + shipment.getReferenceCode() + " - " + route;
            if (!blank(shipment.getCommodity())) description += " - " + shipment.getCommodity();
            if (shipment.getChargeableWeightKg() != null) description += " - " + decimalText(shipment.getChargeableWeightKg()) + " kg chargeable weight";
        }
        return List.of(new InvoiceLine(
                1, "FREIGHT", description,
                BigDecimal.ONE, invoice.getInvoiceAmount(), invoice.getInvoiceAmount(), invoice.getCurrency()));
    }

    private byte[] buildInvoice(CommercialInvoice invoice, Shipment shipment, ClientRecord client, List<InvoiceLine> lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream c = new PDPageContentStream(doc, page);
            try {
                float y = 800;
                text(c, companyName, 42, y, 18, true);
                text(c, "INVOICE", 430, y, 16, true);
                y -= 23;
                if (!blank(companyAddress)) { text(c, companyAddress, 42, y, 8, false); y -= 12; }
                String contact = companyEmail;
                if (!blank(companyPhone)) contact = blank(contact) ? companyPhone : contact + " | " + companyPhone;
                if (!blank(companyWebsite)) contact = blank(contact) ? companyWebsite : contact + " | " + companyWebsite;
                if (!blank(contact)) { text(c, truncate(contact, 90), 42, y, 8, false); y -= 12; }
                String regulatory = "";
                if (!blank(companyRegistrationNumber)) regulatory += "Reg. No: " + companyRegistrationNumber;
                if (!blank(companyTaxId)) regulatory += (regulatory.isBlank() ? "" : " | ") + "Tax ID: " + companyTaxId;
                if (!blank(regulatory)) { text(c, truncate(regulatory, 90), 42, y, 8, false); y -= 12; }

                text(c, "Invoice No.", 430, 777, 8, true);
                text(c, truncate(invoice.getInvoiceNo(), 28), 430, 764, 10, false);
                text(c, "Issue date", 430, 746, 8, true);
                text(c, invoice.getIssueDate() == null ? "—" : DATE.format(invoice.getIssueDate()), 430, 733, 9, false);
                text(c, "Due date", 430, 716, 8, true);
                text(c, invoice.getDueDate() == null ? "Due on receipt" : DATE.format(invoice.getDueDate()), 430, 703, 9, false);

                y = Math.min(y - 12, 715);
                line(c, 42, y, 553);
                y -= 18;
                text(c, "BILL TO", 42, y, 9, true);
                text(c, "INVOICE SUMMARY", 350, y, 9, true);
                y -= 15;
                text(c, truncate(blank(invoice.getClient()) ? "Customer" : invoice.getClient(), 48), 42, y, 10, true);
                text(c, "Shipment: " + (shipment == null ? "—" : safe(shipment.getReferenceCode())), 350, y, 8, false);
                y -= 13;
                text(c, "Currency: " + safe(invoice.getCurrency()), 350, y, 8, false);
                String contactPerson = client == null ? null : client.getContactPerson();
                if (blank(contactPerson) && shipment != null) contactPerson = shipment.getContact();
                if (!blank(contactPerson)) {
                    text(c, truncate("Attention: " + contactPerson, 58), 42, y, 8, false);
                    y -= 12;
                }
                String customerEmail = client == null ? null : client.getEmail();
                if (blank(customerEmail) && shipment != null) customerEmail = shipment.getNotificationEmail();
                String customerPhone = client == null ? null : client.getPhone();
                if (!blank(customerEmail) || !blank(customerPhone)) {
                    String lineText = !blank(customerEmail) ? "Email: " + customerEmail : "";
                    if (!blank(customerPhone)) lineText += (lineText.isBlank() ? "" : " | ") + "Phone: " + customerPhone;
                    text(c, truncate(lineText, 76), 42, y, 8, false);
                    y -= 12;
                }
                if (client != null) {
                    String customerLocation = blank(client.getCity()) ? "" : client.getCity().trim();
                    if (!blank(client.getCountry())) {
                        customerLocation += (customerLocation.isBlank() ? "" : ", ") + client.getCountry().trim();
                    }
                    if (!blank(customerLocation)) {
                        text(c, truncate("Customer location: " + customerLocation, 76), 42, y, 8, false);
                        y -= 12;
                    }
                }
                y -= 6;

                if (shipment != null) {
                    text(c, "SHIPMENT DETAILS", 42, y, 9, true);
                    y -= 14;
                    String origin = firstNonBlank(shipment.getOriginCityPort(), shipment.getOriginAddress(), shipment.getOriginCountry());
                    String destination = firstNonBlank(shipment.getDestinationCityPort(), shipment.getDestinationAddress(), shipment.getDestinationCountry());
                    text(c, "Origin: " + truncate(origin, 55), 42, y, 8, false);
                    text(c, "Destination: " + truncate(destination, 50), 310, y, 8, false);
                    y -= 13;
                    text(c, "Mode: " + safe(shipment.getTransportMode() == null ? null : shipment.getTransportMode().name()), 42, y, 8, false);
                    text(c, "Carrier: " + truncate(firstNonBlank(shipment.getAirlineUsed(), shipment.getCarrierName()), 40), 200, y, 8, false);
                    text(c, "AWB / Reference: " + truncate(firstNonBlank(shipment.getCarrierReferenceNumber(), shipment.getReferenceCode()), 28), 390, y, 8, false);
                    y -= 13;
                    String commodity = "Commodity: " + safe(shipment.getCommodity());
                    text(c, truncate(commodity, 58), 42, y, 8, false);
                    String weight = "Gross: " + decimalText(shipment.getGrossWeightKg()) + " kg";
                    if (shipment.getVolumetricWeightKg() != null) weight += " | Vol: " + decimalText(shipment.getVolumetricWeightKg()) + " kg";
                    if (shipment.getChargeableWeightKg() != null) weight += " | Chargeable: " + decimalText(shipment.getChargeableWeightKg()) + " kg";
                    text(c, truncate(weight, 68), 235, y, 8, false);
                    y -= 13;
                    String packages = shipment.getPackages() == null ? "Packages: —" : "Packages: " + shipment.getPackages();
                    String flight = !blank(shipment.getFlightNumber()) ? "Flight: " + shipment.getFlightNumber() : "Service: " + safe(shipment.getServiceType());
                    text(c, packages, 42, y, 8, false);
                    text(c, truncate(flight, 48), 190, y, 8, false);
                    text(c, "Status: " + safe(shipment.getStatus() == null ? null : shipment.getStatus().name()), 390, y, 8, false);
                    y -= 18;
                } else {
                    y -= 4;
                }

                y = drawInvoiceTableHeader(c, y);
                for (InvoiceLine invoiceLine : lines) {
                    if (y < 105) {
                        c.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        c = new PDPageContentStream(doc, page);
                        y = 800;
                        text(c, companyName, 42, y, 13, true);
                        text(c, "INVOICE CONTINUED", 390, y, 11, true);
                        y -= 24;
                        text(c, "Invoice No: " + invoice.getInvoiceNo(), 42, y, 9, false);
                        y = drawInvoiceTableHeader(c, y - 22);
                    }
                    text(c, truncate(firstNonBlank(invoiceLine.description(), invoiceLine.chargeCode()), 49), 42, y, 8, false);
                    text(c, decimalText(invoiceLine.quantity()), 340, y, 8, false);
                    text(c, money(invoiceLine.unitPrice(), firstNonBlank(invoiceLine.currency(), invoice.getCurrency())), 385, y, 8, false);
                    text(c, money(invoiceLine.amount(), firstNonBlank(invoiceLine.currency(), invoice.getCurrency())), 475, y, 8, false);
                    y -= 16;
                }

                if (y < 210) {
                    c.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    c = new PDPageContentStream(doc, page);
                    y = 800;
                    text(c, companyName, 42, y, 13, true);
                    text(c, "INVOICE SUMMARY", 390, y, 11, true);
                    y -= 26;
                    text(c, "Invoice No: " + invoice.getInvoiceNo(), 42, y, 9, false);
                    y -= 24;
                }
                line(c, 42, y, 553);
                y -= 18;
                text(c, "Invoice subtotal / billed amount", 330, y, 8, false);
                text(c, money(invoice.getInvoiceAmount(), invoice.getCurrency()), 465, y, 8, true);
                y -= 15;
                if (invoice.getDebitNoteAmount() != null && invoice.getDebitNoteAmount().signum() > 0) {
                    text(c, "Debit notes", 330, y, 8, false);
                    text(c, money(invoice.getDebitNoteAmount(), invoice.getCurrency()), 465, y, 8, false);
                    y -= 14;
                }
                if (invoice.getCreditNoteAmount() != null && invoice.getCreditNoteAmount().signum() > 0) {
                    text(c, "Credit notes", 330, y, 8, false);
                    text(c, money(invoice.getCreditNoteAmount().negate(), invoice.getCurrency()), 465, y, 8, false);
                    y -= 14;
                }
                text(c, "Amount paid", 330, y, 8, false);
                text(c, money(invoice.getAmountPaid(), invoice.getCurrency()), 465, y, 8, false);
                y -= 15;
                text(c, "BALANCE DUE", 330, y, 10, true);
                text(c, money(invoice.getBalance(), invoice.getCurrency()), 465, y, 10, true);
                y -= 18;
                text(c, "Payment status: " + safe(invoice.getStatus()), 42, y, 8, true);
                y -= 15;
                if (!blank(invoice.getNotes())) {
                    text(c, "Notes: " + truncate(invoice.getNotes(), 105), 42, y, 8, false);
                    y -= 14;
                }
                if (!blank(paymentInstructions)) {
                    text(c, "PAYMENT INSTRUCTIONS", 42, y, 8, true);
                    y -= 13;
                    text(c, truncate(paymentInstructions, 112), 42, y, 8, false);
                    y -= 14;
                }
                text(c, "Please quote invoice " + safe(invoice.getInvoiceNo()) + " with your payment.", 42, 76, 8, false);
                text(c, "Thank you for your business.", 42, 60, 8, true);
            } finally {
                if (c != null) c.close();
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate invoice PDF", e);
        }
    }

    private static float drawInvoiceTableHeader(PDPageContentStream c, float y) throws Exception {
        line(c, 42, y + 8, 553);
        text(c, "DESCRIPTION", 42, y - 4, 8, true);
        text(c, "QTY", 340, y - 4, 8, true);
        text(c, "UNIT PRICE", 385, y - 4, 8, true);
        text(c, "AMOUNT", 475, y - 4, 8, true);
        line(c, 42, y - 11, 553);
        return y - 26;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value;
        return "—";
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private byte[] buildReceipt(CommercialPayment payment, CommercialInvoice invoice, Shipment shipment) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                float y = 800;
                text(c, companyName, 42, y, 18, true);
                text(c, "PAYMENT RECEIPT", 360, y, 15, true);
                y -= 26;
                text(c, companyAddress, 42, y, 9, false);
                text(c, "Receipt: " + receiptNumber(payment), 360, y, 9, false);
                y -= 14;
                text(c, companyEmail + (blank(companyPhone) ? "" : " | " + companyPhone), 42, y, 9, false);
                text(c, "Payment: " + payment.getCreatedAt(), 360, y, 9, false);

                y -= 45;
                text(c, "RECEIVED FROM", 42, y, 10, true);
                text(c, invoice.getClient(), 42, y - 16, 11, false);
                text(c, "Invoice: " + invoice.getInvoiceNo(), 42, y - 32, 10, false);
                if (shipment != null) text(c, "Shipment: " + shipment.getReferenceCode(), 42, y - 48, 10, false);

                y -= 90;
                text(c, "AMOUNT RECEIVED", 42, y, 11, true);
                text(c, money(payment.getAmount(), payment.getCurrency()), 320, y, 14, true);
                y -= 28;
                text(c, "Payment reference", 42, y, 9, false);
                text(c, safe(payment.getReference()), 320, y, 9, false);
                y -= 18;
                text(c, "Invoice balance after payment", 42, y, 9, false);
                text(c, money(invoice.getBalance(), invoice.getCurrency()), 320, y, 9, true);
                y -= 46;
                line(c, 42, y, 550);
                y -= 24;
                text(c, "This receipt confirms that the payment was recorded in the AAL accounting system.", 42, y, 9, false);
                text(c, "Thank you for your business.", 42, 64, 9, false);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate payment receipt PDF", e);
        }
    }

    private static void text(PDPageContentStream c, String value, float x, float y, float size, boolean bold) throws Exception {
        c.beginText();
        c.setFont(bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, size);
        c.newLineAtOffset(x, y);
        c.showText(safe(value));
        c.endText();
    }

    private static void line(PDPageContentStream c, float x1, float y, float x2) throws Exception {
        c.moveTo(x1, y); c.lineTo(x2, y); c.stroke();
    }

    private static String truncate(String value, int max) {
        String v = safe(value);
        return v.length() <= max ? v : v.substring(0, max - 1) + "...";
    }

    private static String money(BigDecimal value, String currency) {
        return safe(currency) + " " + nz(value, BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value, BigDecimal fallback) { return value == null ? fallback : value; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String esc(String value) { return safe(value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    private static String email(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (!v.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recipient email");
        return v;
    }
   private static String receiptNumber(CommercialPayment payment) {
        if (payment.getReceiptNo() != null && !payment.getReceiptNo().isBlank()) return payment.getReceiptNo();
        return "PAY-" + payment.getId();
    }

    private UUID requireTenant() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) throw new IllegalStateException("Tenant context is required");
        return tenant;
    }

    public record ReceiptReference(UUID paymentId, String receiptNo, BigDecimal amount, String currency, String paidAt) {}

    private record InvoiceLine(int lineNo, String chargeCode, String description,
                               BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, String currency) {}
}
