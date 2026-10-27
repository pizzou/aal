package com.logiplatform;

import com.logiplatform.service.AalExcelPreflightService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AalExcelPreflightServiceTest {
    private static final String[] HEADER = {
            "DATE", "AWB NO", "NAME OF CLIENT", "CONTACT", "COMMODITY", "ORIGIN", "DESTINATION",
            "GROSS WEIGHT (KG)", "VOLUMETRIC WEIGHT (KG)", "CHARGEABLE WEIGHT (KG)",
            "SERVICE TYPE", "AIRLINE USED", "SHIPMENT STATUS", "OPERATOR",
            "AMOUNT BILLED TO CLIENT", "AMOUNT PAID BY CLIENT", "AMOUNT REMAINING",
            "AMOUNT PAID TO SUPPLY", "OTHER EXPENSES", "NET INCOME", "PAYMENT STATUS"
    };

    private MultipartFile sample(boolean invalidCost, boolean inconsistentFinance) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("August");
            var header = sheet.createRow(0);
            for (int c = 0; c < HEADER.length; c++) header.createCell(c).setCellValue(HEADER[c]);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("2026-08-01");
            row.createCell(1).setCellValue("071-61746230");
            row.createCell(2).setCellValue("Example Client");
            row.createCell(5).setCellValue("KGL");
            row.createCell(6).setCellValue("CAN");
            row.createCell(14).setCellValue(1000);
            row.createCell(15).setCellValue(800);
            row.createCell(16).setCellValue(200);
            if (invalidCost) row.createCell(17).setCellValue("-");
            else row.createCell(17).setCellValue(400);
            row.createCell(18).setCellValue(0);
            row.createCell(19).setCellValue(600);

            if (inconsistentFinance) {
                var finance = book.createSheet("August Finance");
                finance.createRow(0).createCell(8).setCellValue("AUGUST FINANCIAL SUMMARY");
                finance.createRow(1).createCell(9).setCellValue(900);
                finance.createRow(2).createCell(9).setCellValue(800);
                finance.createRow(4).createCell(9).setCellValue(400);
            }
            book.write(output);
            return new MockMultipartFile("files", "MOTHERSHIP.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    @Test
    void acceptsConsistentAWBRowsButWarnsAboutMissingPaymentDetail() throws Exception {
        var preview = new AalExcelPreflightService().preview(List.of(sample(false, false)));
        assertEquals(1, preview.shipmentRows());
        assertEquals(1, preview.distinctAwbs());
        assertEquals(0, preview.blockers());
        assertTrue(preview.readyToImport());
        assertEquals("200", preview.receivable().stripTrailingZeros().toPlainString());
        assertTrue(preview.warnings() > 0);
    }

    @Test
    void refusesUnknownSupplierCashAmount() throws Exception {
        var preview = new AalExcelPreflightService().preview(List.of(sample(true, false)));
        assertFalse(preview.readyToImport());
        assertTrue(preview.issues().stream().anyMatch(i -> i.code().equals("UNKNOWN_SUPPLIER_AMOUNT")));
    }

    @Test
    void refusesDisagreementBetweenFinanceSheetAndShipmentRegister() throws Exception {
        var preview = new AalExcelPreflightService().preview(List.of(sample(false, true)));
        assertFalse(preview.readyToImport());
        assertTrue(preview.issues().stream().anyMatch(i -> i.code().equals("FINANCE_SHEET_DISAGREEMENT")));
    }

    @Test
    void refusesImpossibleCustomerReceivable() throws Exception {
        var input = sample(false, false);
        try (XSSFWorkbook book = new XSSFWorkbook(input.getInputStream()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            book.getSheetAt(0).getRow(1).getCell(15).setCellValue(1200);
            book.write(out);
            var preview = new AalExcelPreflightService().preview(List.of(new MockMultipartFile(
                    "files", "MOTHERSHIP.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray())));
            assertTrue(preview.blockers() > 0);
            assertTrue(preview.issues().stream().anyMatch(i -> i.code().equals("INVALID_RECEIVABLE")));
        }
    }
}
