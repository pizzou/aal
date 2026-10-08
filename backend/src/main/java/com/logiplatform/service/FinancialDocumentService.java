package com.logiplatform.service;

import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.model.CommercialPayment;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.repository.CommercialPaymentRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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

    public FinancialDocumentService(
            CommercialInvoiceRepository invoices,
            CommercialPaymentRepository payments,
            ShipmentRepository shipments,
            @Qualifier("tenantJdbcTemplate") JdbcTemplate tenantDb,
            MailService mail,
            FinancialDocumentArchiveService archive) {
        this.invoices = invoices;
        this.payments = payments;
        this.shipments = shipments;
        this.tenantDb = tenantDb;
        this.mail = mail;
        this.archive = archive;
    }

    @Transactional(readOnly = true)
    public byte[] invoicePdf(UUID invoiceId) {
        CommercialInvoice invoice = invoice(invoiceId);
        Shipment shipment = shipment(invoice.getShipmentId());
        List<InvoiceLine> lines = lines(invoiceId);
        if (lines.isEmpty()) lines = fallbackLines(invoice, shipment);
        byte[] pdf = buildInvoice(invoice, shipment, lines);
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

    private List<InvoiceLine> fallbackLines(CommercialInvoice invoice, Shipment shipment) {
        return List.of(new InvoiceLine(
                1, "FREIGHT",
                shipment == null ? "Logistics services" : "Logistics services - " + shipment.getReferenceCode(),
                BigDecimal.ONE, invoice.getInvoiceAmount(), invoice.getInvoiceAmount(), invoice.getCurrency()));
    }

    private byte[] buildInvoice(CommercialInvoice invoice, Shipment shipment, List<InvoiceLine> lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                float y = 800;
                text(c, companyName, 42, y, 18, true);
                text(c, "INVOICE", 430, y, 17, true);
                y -= 24;
                text(c, companyAddress, 42, y, 9, false);
                text(c, "Invoice: " + invoice.getInvoiceNo(), 430, y, 9, false);
                y -= 14;
                text(c, companyEmail + (blank(companyPhone) ? "" : " | " + companyPhone), 42, y, 9, false);
                text(c, "Issue: " + DATE.format(invoice.getIssueDate()), 430, y, 9, false);

                y -= 40;
                text(c, "BILL TO", 42, y, 10, true);
                text(c, invoice.getClient(), 42, y - 15, 10, false);
                if (shipment != null) text(c, shipment.getNotificationEmail(), 42, y - 29, 9, false);
                text(c, "Due", 430, y, 9, true);
                text(c, invoice.getDueDate() == null ? "Due on receipt" : DATE.format(invoice.getDueDate()), 430, y - 15, 9, false);
                if (shipment != null) text(c, "Shipment: " + shipment.getReferenceCode(), 430, y - 29, 9, false);

                y -= 70;
                text(c, "DESCRIPTION", 42, y, 9, true);
                text(c, "QTY", 350, y, 9, true);
                text(c, "AMOUNT", 440, y, 9, true);
                y -= 16;
                for (InvoiceLine line : lines) {
                    if (y < 130) break;
                    text(c, truncate(line.description(), 48), 42, y, 9, false);
                    text(c, line.quantity().stripTrailingZeros().toPlainString(), 350, y, 9, false);
                    text(c, money(line.amount(), line.currency()), 440, y, 9, false);
                    y -= 15;
                }
                y -= 8;
                line(c, 42, y, 550);
                y -= 20;
                text(c, "Invoice total", 390, y, 9, false);
                text(c, money(invoice.getInvoiceAmount(), invoice.getCurrency()), 470, y, 9, true);
                y -= 16;
                if (invoice.getDebitNoteAmount().signum() > 0) {
                    text(c, "Debit notes", 390, y, 9, false);
                    text(c, money(invoice.getDebitNoteAmount(), invoice.getCurrency()), 470, y, 9, false);
                    y -= 16;
                }
                if (invoice.getCreditNoteAmount().signum() > 0) {
                    text(c, "Credit notes", 390, y, 9, false);
                    text(c, money(invoice.getCreditNoteAmount().negate(), invoice.getCurrency()), 470, y, 9, false);
                    y -= 16;
                }
                text(c, "Paid", 390, y, 9, false);
                text(c, money(invoice.getAmountPaid(), invoice.getCurrency()), 470, y, 9, false);
                y -= 16;
                text(c, "Balance", 390, y, 10, true);
                text(c, money(invoice.getBalance(), invoice.getCurrency()), 470, y, 10, true);
                y -= 30;
                text(c, "Status: " + invoice.getStatus(), 42, y, 9, true);
                if (!blank(invoice.getNotes())) text(c, truncate(invoice.getNotes(), 100), 42, y - 16, 8, false);
                text(c, "Thank you for your business.", 42, 64, 9, false);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate invoice PDF", e);
        }
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
                text(c, "Receipt: " + safe(payment.getReceiptNo()), 360, y, 9, false);
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
