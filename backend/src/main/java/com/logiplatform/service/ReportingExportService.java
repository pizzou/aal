package com.logiplatform.service;

import com.logiplatform.dto.ReportingDtos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReportingExportService {

    private final ReportingService reporting;

    public ReportingExportService(ReportingService reporting) {
        this.reporting = reporting;
    }

    public byte[] xlsx(LocalDate from, LocalDate to) {
        ManagementReport r = reporting.managementReport(from, to);

        try (
                Workbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            summarySheet(wb, r);
            customersSheet(wb, r);
            shipmentsSheet(wb, r);
            receivablesSheet(wb, r);
            monthlySheet(wb, r);
            exceptionsSheet(wb, r);

            for (Sheet s : wb) {
                Row firstRow = s.getRow(0);

                int columnCount = firstRow == null
                        ? 0
                        : Math.max(firstRow.getLastCellNum(), 0);

                for (int i = 0; i < Math.min(columnCount, 14); i++) {
                    s.autoSizeColumn(i);
                }
            }

            wb.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to generate XLSX report",
                    e
            );
        }
    }

    public byte[] csv(LocalDate from, LocalDate to) {
        ManagementReport r = reporting.managementReport(from, to);

        StringBuilder b = new StringBuilder();

        section(b, "REPORT");
        row(b, "From", r.from());
        row(b, "To", r.to());
        row(b, "Currency", r.currency());
        row(b, "Mixed currencies", r.mixedCurrencies());

        section(b, "FINANCE");
        row(b, "Invoiced revenue", r.financial().invoicedRevenue());
        row(b, "Collected revenue", r.financial().collectedRevenue());
        row(
                b,
                "Outstanding receivables",
                r.financial().outstandingReceivables()
        );
        row(
                b,
                "Overdue receivables",
                r.financial().overdueReceivables()
        );
        row(b, "Supplier costs", r.financial().supplierCosts());
        row(b, "Other costs", r.financial().otherCosts());
        row(b, "Gross profit", r.financial().grossProfit());
        row(b, "Net profit", r.financial().netProfit());

        section(b, "CUSTOMER PROFITABILITY");

        row(
                b,
                "Customer",
                "Shipments",
                "Revenue",
                "Supplier cost",
                "Other cost",
                "Gross profit",
                "Margin %"
        );

        for (CustomerProfitability x : r.customerProfitability()) {
            row(
                    b,
                    x.customer(),
                    x.shipments(),
                    x.revenue(),
                    x.supplierCost(),
                    x.otherCost(),
                    x.grossProfit(),
                    x.marginPercent()
            );
        }

        section(b, "SHIPMENT PROFITABILITY");

        row(
                b,
                "Reference",
                "Date",
                "Customer",
                "Carrier",
                "Mode",
                "Currency",
                "Revenue",
                "Supplier cost",
                "Other cost",
                "Gross profit",
                "Margin %",
                "Status"
        );

        for (ShipmentProfitability x : r.shipmentProfitability()) {
            row(
                    b,
                    x.shipmentReference(),
                    x.dateOpened(),
                    x.customer(),
                    x.carrier(),
                    x.mode(),
                    x.currency(),
                    x.revenue(),
                    x.supplierCost(),
                    x.otherCost(),
                    x.grossProfit(),
                    x.marginPercent(),
                    x.status()
            );
        }

        section(b, "RECEIVABLES AGING");

        row(
                b,
                "Bucket",
                "Balance",
                "Invoice count"
        );

        for (ReceivablesAging x : r.receivablesAging()) {
            row(
                    b,
                    x.bucket(),
                    x.balance(),
                    x.invoiceCount()
            );
        }

        section(b, "MONTHLY TREND");

        row(
                b,
                "Month",
                "Shipments",
                "Revenue",
                "Collected",
                "Outstanding",
                "Supplier payments",
                "Other expenses",
                "Gross profit",
                "Net income",
                "Margin %"
        );

        for (MonthlyTrend x : r.monthlyTrend()) {
            row(
                    b,
                    x.month(),
                    x.shipments(),
                    x.invoicedRevenue(),
                    x.collectedRevenue(),
                    x.outstandingReceivables(),
                    x.supplierPayments(),
                    x.otherExpenses(),
                    x.grossProfit(),
                    x.netIncome(),
                    x.profitMarginPercent()
            );
        }

        return b.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] pdf(LocalDate from, LocalDate to) {
        ManagementReport r = reporting.managementReport(from, to);

        try (
                PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()
        ) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream c =
                         new PDPageContentStream(doc, page)) {

                /*
                 * PDFBox coordinates are floating-point values.
                 * Keep the vertical coordinate as float throughout
                 * the PDF rendering process.
                 */
                float y = 800.0f;

                text(
                        c,
                        "AAL MANAGEMENT REPORT",
                        42.0f,
                        y,
                        18.0f,
                        true
                );

                y -= 22.0f;

                text(
                        c,
                        "Period: "
                                + r.from()
                                + " to "
                                + r.to()
                                + " | Currency: "
                                + r.currency(),
                        42.0f,
                        y,
                        9.0f,
                        false
                );

                y -= 36.0f;

                y = metric(
                        c,
                        "Revenue",
                        money(
                                r.financial().invoicedRevenue(),
                                r.currency()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Collected",
                        money(
                                r.financial().collectedRevenue(),
                                r.currency()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Outstanding",
                        money(
                                r.financial().outstandingReceivables(),
                                r.currency()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Gross profit",
                        money(
                                r.financial().grossProfit(),
                                r.currency()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Net profit",
                        money(
                                r.financial().netProfit(),
                                r.currency()
                        ),
                        y
                );

                y -= 18.0f;

                text(
                        c,
                        "OPERATIONS",
                        42.0f,
                        y,
                        11.0f,
                        true
                );

                y -= 18.0f;

                y = metric(
                        c,
                        "Shipments",
                        String.valueOf(
                                r.operations().totalShipments()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Completed",
                        String.valueOf(
                                r.operations().completedShipments()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Delayed",
                        String.valueOf(
                                r.operations().delayedShipments()
                        ),
                        y
                );

                y = metric(
                        c,
                        "Exceptions",
                        String.valueOf(
                                r.operations().exceptionShipments()
                        ),
                        y
                );

                y = metric(
                        c,
                        "On-time rate",
                        r.operations().onTimeRatePercent() + "%",
                        y
                );

                y -= 18.0f;

                text(
                        c,
                        "TOP CUSTOMERS BY GROSS PROFIT",
                        42.0f,
                        y,
                        11.0f,
                        true
                );

                y -= 18.0f;

                int count = 0;

                for (CustomerProfitability x :
                        r.customerProfitability()) {

                    if (y < 80.0f || count++ >= 12) {
                        break;
                    }

                    text(
                            c,
                            truncate(x.customer(), 36),
                            42.0f,
                            y,
                            8.0f,
                            false
                    );

                    text(
                            c,
                            money(
                                    x.grossProfit(),
                                    r.currency()
                            ),
                            410.0f,
                            y,
                            8.0f,
                            true
                    );

                    y -= 13.0f;
                }

                text(
                        c,
                        "Generated from the controlled AAL reporting data model.",
                        42.0f,
                        48.0f,
                        8.0f,
                        false
                );
            }

            doc.save(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to generate PDF report",
                    e
            );
        }
    }

    private static void summarySheet(
            Workbook wb,
            ManagementReport r
    ) {
        Sheet s = wb.createSheet("Summary");

        int row = 0;

        cell(
                s,
                row++,
                "AAL MANAGEMENT REPORT"
        );

        row++;

        row = pair(
                s,
                row,
                "From",
                r.from()
        );

        row = pair(
                s,
                row,
                "To",
                r.to()
        );

        row = pair(
                s,
                row,
                "Currency",
                r.currency()
        );

        row = pair(
                s,
                row,
                "Mixed currencies",
                r.mixedCurrencies()
        );

        row++;

        row = pair(
                s,
                row,
                "Revenue",
                r.financial().invoicedRevenue()
        );

        row = pair(
                s,
                row,
                "Collected",
                r.financial().collectedRevenue()
        );

        row = pair(
                s,
                row,
                "Outstanding",
                r.financial().outstandingReceivables()
        );

        row = pair(
                s,
                row,
                "Overdue",
                r.financial().overdueReceivables()
        );

        row = pair(
                s,
                row,
                "Supplier costs",
                r.financial().supplierCosts()
        );

        row = pair(
                s,
                row,
                "Other costs",
                r.financial().otherCosts()
        );

        row = pair(
                s,
                row,
                "Gross profit",
                r.financial().grossProfit()
        );

        row = pair(
                s,
                row,
                "Net profit",
                r.financial().netProfit()
        );

        row = pair(
                s,
                row,
                "Collection rate %",
                r.financial().collectionRatePercent()
        );

        row = pair(
                s,
                row,
                "On-time rate %",
                r.operations().onTimeRatePercent()
        );
    }

    private static void customersSheet(
            Workbook wb,
            ManagementReport r
    ) {
        Sheet s =
                wb.createSheet("Customer Profitability");

        headers(
                s,
                "Customer",
                "Shipments",
                "Revenue",
                "Supplier Cost",
                "Other Cost",
                "Gross Profit",
                "Margin %"
        );

        int row = 1;

        for (CustomerProfitability x :
                r.customerProfitability()) {

            values(
                    s,
                    row++,
                    x.customer(),
                    x.shipments(),
                    x.revenue(),
                    x.supplierCost(),
                    x.otherCost(),
                    x.grossProfit(),
                    x.marginPercent()
            );
        }
    }

    private static void shipmentsSheet(
            Workbook wb,
            ManagementReport r
    ) {
        Sheet s =
                wb.createSheet("Shipment Profitability");

        headers(
                s,
                "Reference",
                "Date",
                "Customer",
                "Carrier",
                "Mode",
                "Currency",
                "Revenue",
                "Supplier Cost",
                "Other Cost",
                "Gross Profit",
                "Margin %",
                "Status"
        );

        int row = 1;

        for (ShipmentProfitability x :
                r.shipmentProfitability()) {

            values(
                    s,
                    row++,
                    x.shipmentReference(),
                    x.dateOpened(),
                    x.customer(),
                    x.carrier(),
                    x.mode(),
                    x.currency(),
                    x.revenue(),
                    x.supplierCost(),
                    x.otherCost(),
                    x.grossProfit(),
                    x.marginPercent(),
                    x.status()
            );
        }
    }

    private static void receivablesSheet(
            Workbook wb,
            ManagementReport r
    ) {
        Sheet s =
                wb.createSheet("Receivables Aging");

        headers(
                s,
                "Bucket",
                "Balance",
                "Invoice Count"
        );

        int row = 1;

        for (ReceivablesAging x :
                r.receivablesAging()) {

            values(
                    s,
                    row++,
                    x.bucket(),
                    x.balance(),
                    x.invoiceCount()
            );
        }
    }

    private static void monthlySheet(
            Workbook wb,
            ManagementReport r
    ) {
        Sheet s =
                wb.createSheet("Monthly Trend");

        headers(
                s,
                "Month",
                "Shipments",
                "Revenue",
                "Collected",
                "Outstanding",
                "Supplier Payments",
                "Other Expenses",
                "Gross Profit",
                "Net Income",
                "Margin %"
        );

        int row = 1;

        for (MonthlyTrend x :
                r.monthlyTrend()) {

            values(
                    s,
                    row++,
                    x.month(),
                    x.shipments(),
                    x.invoicedRevenue(),
                    x.collectedRevenue(),
                    x.outstandingReceivables(),
                    x.supplierPayments(),
                    x.otherExpenses(),
                    x.grossProfit(),
                    x.netIncome(),
                    x.profitMarginPercent()
            );
        }
    }

    private static void exceptionsSheet(
            Workbook wb,
            ManagementReport r
    ) {
        Sheet s =
                wb.createSheet("Exceptions");

        headers(
                s,
                "Severity",
                "Type",
                "Reference",
                "Message",
                "Lane",
                "Mode"
        );

        int row = 1;

        for (ExceptionSummary x :
                r.operationalExceptions()) {

            values(
                    s,
                    row++,
                    x.severity(),
                    x.type(),
                    x.reference(),
                    x.message(),
                    x.lane(),
                    x.mode()
            );
        }
    }

    private static void headers(
            Sheet s,
            String... values
    ) {
        Row row = s.createRow(0);

        for (int i = 0; i < values.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(values[i]);

            CellStyle style =
                    s.getWorkbook().createCellStyle();

            Font font =
                    s.getWorkbook().createFont();

            font.setBold(true);
            style.setFont(font);
            c.setCellStyle(style);
        }
    }

    private static void values(
            Sheet s,
            int row,
            Object... values
    ) {
        Row r = s.createRow(row);

        for (int i = 0; i < values.length; i++) {
            Object v = values[i];

            Cell c = r.createCell(i);

            if (v instanceof Number n) {
                c.setCellValue(n.doubleValue());
            } else {
                c.setCellValue(
                        v == null
                                ? ""
                                : String.valueOf(v)
                );
            }
        }
    }

    private static int pair(
            Sheet s,
            int row,
            String key,
            Object value
    ) {
        values(
                s,
                row,
                key,
                value
        );

        return row + 1;
    }

    private static void cell(
            Sheet s,
            int row,
            String value
    ) {
        s.createRow(row)
                .createCell(0)
                .setCellValue(value);
    }

    private static void section(
            StringBuilder b,
            String title
    ) {
        b.append("\n")
                .append(csv(title))
                .append("\n");
    }

    private static void row(
            StringBuilder b,
            Object... values
    ) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(',');
            }

            b.append(csv(values[i]));
        }

        b.append('\n');
    }

    private static String csv(Object v) {
        String s =
                v == null
                        ? ""
                        : String.valueOf(v);

        return "\""
                + s.replace("\"", "\"\"")
                + "\"";
    }

   
    private static float metric(
            PDPageContentStream c,
            String label,
            String value,
            float y
    ) throws Exception {

        text(
                c,
                label,
                42.0f,
                y,
                9.0f,
                false
        );

        text(
                c,
                value,
                300.0f,
                y,
                9.0f,
                true
        );

        return y - 15.0f;
    }

    private static void text(
            PDPageContentStream c,
            String value,
            float x,
            float y,
            float size,
            boolean bold
    ) throws Exception {

        c.beginText();

        c.setFont(
                bold
                        ? PDType1Font.HELVETICA_BOLD
                        : PDType1Font.HELVETICA,
                size
        );

        c.newLineAtOffset(x, y);

        c.showText(
                value == null
                        ? ""
                        : value
        );

        c.endText();
    }

    private static String money(
            BigDecimal n,
            String currency
    ) {
        BigDecimal amount =
                n == null
                        ? BigDecimal.ZERO
                        : n;

        return (currency == null ? "" : currency)
                + " "
                + amount
                .setScale(
                        2,
                        java.math.RoundingMode.HALF_UP
                )
                .toPlainString();
    }

    private static String truncate(
            String s,
            int n
    ) {
        if (s == null) {
            return "";
        }

        if (n <= 3) {
            return s.length() <= n
                    ? s
                    : s.substring(0, n);
        }

        return s.length() <= n
                ? s
                : s.substring(0, n - 1) + "...";
    }
}
