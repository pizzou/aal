package com.logiplatform;

import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.model.CommercialPayment;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.repository.CommercialPaymentRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.service.FinancialDocumentArchiveService;
import com.logiplatform.service.FinancialDocumentService;
import com.logiplatform.service.MailService;
import com.logiplatform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialDocumentServiceTest {
    private final UUID tenant=UUID.randomUUID();
    private final UUID invoiceId=UUID.randomUUID();
    private final UUID paymentId=UUID.randomUUID();

    @AfterEach
    void clearTenant(){ TenantContext.clear(); }

    @Test
    void invoicePdfIsGeneratedAndArchived() throws Exception {
        TenantContext.setTenantId(tenant);
        CommercialInvoiceRepository invoices=mock(CommercialInvoiceRepository.class);
        CommercialPaymentRepository payments=mock(CommercialPaymentRepository.class);
        ShipmentRepository shipments=mock(ShipmentRepository.class);
        JdbcTemplate db=mock(JdbcTemplate.class);
        MailService mail=mock(MailService.class);
        FinancialDocumentArchiveService archive=mock(FinancialDocumentArchiveService.class);

        CommercialInvoice invoice=mock(CommercialInvoice.class);
        Shipment shipment=mock(Shipment.class);
        when(invoices.findByTenantIdAndId(tenant,invoiceId)).thenReturn(Optional.of(invoice));
        when(invoice.getId()).thenReturn(invoiceId);
        when(invoice.getInvoiceNo()).thenReturn("AAL-INV-2026-000001");
        when(invoice.getIssueDate()).thenReturn(LocalDate.of(2026,10,1));
        when(invoice.getClient()).thenReturn("Test Client");
        when(invoice.getShipmentId()).thenReturn(UUID.randomUUID());
        when(invoice.getCurrency()).thenReturn("USD");
        when(invoice.getInvoiceAmount()).thenReturn(new BigDecimal("1000"));
        when(invoice.getAmountPaid()).thenReturn(new BigDecimal("200"));
        when(invoice.getBalance()).thenReturn(new BigDecimal("800"));
        when(invoice.getDueDate()).thenReturn(LocalDate.of(2026,10,31));
        when(invoice.getStatus()).thenReturn("Partially Paid");
        when(invoice.getNotes()).thenReturn(null);
        when(invoice.getCreditNoteAmount()).thenReturn(BigDecimal.ZERO);
        when(invoice.getDebitNoteAmount()).thenReturn(BigDecimal.ZERO);
        when(shipments.findByIdAndTenantId(any(UUID.class),eq(tenant))).thenReturn(Optional.of(shipment));
        when(shipment.getReferenceCode()).thenReturn("AAL-SHP-2026-000001");
        when(shipment.getNotificationEmail()).thenReturn("client@example.com");
        when(db.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(tenant), eq(invoiceId)))
                .thenReturn(Collections.emptyList());
        when(archive.archive(eq("INVOICE"),eq(invoiceId),anyString(),eq("application/pdf"),any(byte[].class)))
                .thenReturn(UUID.randomUUID());

        FinancialDocumentService service=new FinancialDocumentService(invoices,payments,shipments,db,mail,archive);
        byte[] pdf=service.invoicePdf(invoiceId);

        assertNotNull(pdf);
        assertTrue(pdf.length>100);
        try (PDDocument document = PDDocument.load(pdf)) {
            String renderedText = new PDFTextStripper().getText(document);
            assertTrue(renderedText.contains("AAL-INV-2026-000001"));
            assertTrue(renderedText.contains("AAL-SHP-2026-000001"));
            assertTrue(renderedText.contains("BALANCE DUE"));
        }
        verify(archive).archive(eq("INVOICE"),eq(invoiceId),anyString(),eq("application/pdf"),any(byte[].class));
    }

    @Test
    void receiptPdfAndEmailUseDurableReceiptIdentity() {
        TenantContext.setTenantId(tenant);
        CommercialInvoiceRepository invoices=mock(CommercialInvoiceRepository.class);
        CommercialPaymentRepository payments=mock(CommercialPaymentRepository.class);
        ShipmentRepository shipments=mock(ShipmentRepository.class);
        JdbcTemplate db=mock(JdbcTemplate.class);
        MailService mail=mock(MailService.class);
        FinancialDocumentArchiveService archive=mock(FinancialDocumentArchiveService.class);

        CommercialPayment payment=mock(CommercialPayment.class);
        CommercialInvoice invoice=mock(CommercialInvoice.class);
        Shipment shipment=mock(Shipment.class);
        when(payments.findByTenantIdAndId(tenant,paymentId)).thenReturn(Optional.of(payment));
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getInvoiceId()).thenReturn(invoiceId);
        when(payment.getReceiptNo()).thenReturn("AAL-RCT-2026-000001");
        when(payment.getAmount()).thenReturn(new BigDecimal("250"));
        when(payment.getCurrency()).thenReturn("USD");
        when(payment.getCreatedAt()).thenReturn(Instant.parse("2026-10-01T10:00:00Z"));
        when(payment.getReference()).thenReturn("BANK-001");
        when(invoices.findByTenantIdAndId(tenant,invoiceId)).thenReturn(Optional.of(invoice));
        when(invoice.getInvoiceNo()).thenReturn("AAL-INV-2026-000001");
        when(invoice.getClient()).thenReturn("Test Client");
        UUID shipmentId=UUID.randomUUID();
        when(invoice.getShipmentId()).thenReturn(shipmentId);
        when(invoice.getBalance()).thenReturn(new BigDecimal("750"));
        when(invoice.getCurrency()).thenReturn("USD");
        when(shipments.findByIdAndTenantId(shipmentId,tenant)).thenReturn(Optional.of(shipment));
        when(shipment.getReferenceCode()).thenReturn("AAL-SHP-2026-000001");
        when(archive.archive(eq("RECEIPT"),eq(paymentId),anyString(),eq("application/pdf"),any(byte[].class)))
                .thenReturn(UUID.randomUUID());

        FinancialDocumentService service=new FinancialDocumentService(invoices,payments,shipments,db,mail,archive);
        byte[] pdf=service.receiptPdf(paymentId);
        assertNotNull(pdf);
        assertTrue(pdf.length>100);

        service.emailReceipt(paymentId,"client@example.com","Test Client");
        verify(mail).sendFinancialDocument(eq("client@example.com"),eq("Test Client"),
                contains("AAL-RCT-2026-000001"),anyString(),
                eq("receipt-AAL-RCT-2026-000001.pdf"),any(byte[].class));
    }
}
