package com.logiplatform.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only preflight for the historical AAL MOTHERSHIP registers.
 *
 * The importer MUST call this before writing. A preview is not a ledger
 * reconciliation or permission to retire historical accounting records.
 */
@Service
public class AalExcelPreflightService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");
    private static final int MAX_ISSUES = 200;
    private static final int MAX_FILES = 20;
    private static final long MAX_SINGLE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    private static final Set<String> MONTHS = Set.of("january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december");

    public record Issue(String workbook, String sheet, int row, String severity, String code, String message) {}

    public record Preview(int shipmentRows, int distinctAwbs, BigDecimal billed, BigDecimal collected,
            BigDecimal receivable, BigDecimal supplierPayments, int blockers, int warnings,
            boolean readyToImport, List<String> sheetsReviewed, List<Issue> issues) {}

    public Preview preview(List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Provide between 1 and 20 Excel workbooks");
        }
        int rows = 0;
        long bytes = 0;
        BigDecimal billed = BigDecimal.ZERO, collected = BigDecimal.ZERO, receivable = BigDecimal.ZERO;
        BigDecimal supplierPayments = BigDecimal.ZERO;
        int blockers = 0, warnings = 0;
        List<Issue> issues = new ArrayList<>();
        List<String> reviewed = new ArrayList<>();
        Set<String> awbs = new HashSet<>();
        Map<String, Totals> monthly = new HashMap<>();
        boolean sawMothership = false;
        boolean warnedCurrency = false;

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty() || file.getSize() > MAX_SINGLE_BYTES) {
                throw new IllegalArgumentException("Each Excel workbook must be nonempty and at most 50 MB");
            }
            String filename = file.getOriginalFilename() == null ? "AAL workbook" : file.getOriginalFilename();
            String name = filename.toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls"))
                    || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                throw new IllegalArgumentException("Upload a valid .xlsx, .xlsm or .xls workbook filename");
            }
            bytes += file.getSize();
            if (bytes > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Combined workbooks exceed 200 MB");
            try (InputStream input = file.getInputStream(); Workbook workbook = WorkbookFactory.create(input)) {
                if (workbook.getNumberOfSheets() > 100) {
                    throw new IllegalArgumentException("Workbook has more than 100 sheets");
                }
                for (Sheet sheet : workbook) {
                    if (sheet.getLastRowNum() > 200_000) {
                        throw new IllegalArgumentException("Worksheet " + sheet.getSheetName() + " exceeds the row limit");
                    }
                    Map<String, Integer> headers = headers(sheet.getRow(0));
                    if (!headers.keySet().containsAll(Set.of("awb no", "name of client", "destination", "amount billed to client"))) {
                        continue;
                    }
                    sawMothership = true;
                    reviewed.add(filename + " / " + sheet.getSheetName());
                    if (!warnedCurrency) {
                        warnedCurrency = true;
                        warnings = add(issues, warnings, filename, sheet.getSheetName(), 1, "WARNING",
                                "CURRENCY_ASSUMED", "MOTHERSHIP has no currency column; historical import assumes USD. Confirm each amount is USD.");
                        warnings = add(issues, warnings, filename, sheet.getSheetName(), 1, "WARNING",
                                "PAYMENT_HISTORY_MISSING", "Cumulative amounts do not establish individual receipt dates, methods, invoice allocation or bank reconciliation.");
                    }
                    Totals totals = monthly.computeIfAbsent(sheet.getSheetName().toLowerCase(Locale.ROOT), k -> new Totals());
                    for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                        Row row = sheet.getRow(r);
                        if (row == null) continue;
                        String awb = cell(row, headers, "awb no").trim();
                        if (awb.isEmpty()) {
                            if (!cell(row, headers, "date").isBlank() || !cell(row, headers, "shipment status").isBlank()
                                    || !cell(row, headers, "name of client").isBlank()) {
                                warnings = add(issues, warnings, filename, sheet.getSheetName(), r + 1, "WARNING",
                                        "SKIPPED_NO_AWB", "This partially populated row has no AWB and will not be imported.");
                            }
                            continue;
                        }
                        rows++;
                        String uniqueAwb = awb.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
                        if (!awbs.add(uniqueAwb)) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "DUPLICATE_AWB", "The AWB " + awb + " appears more than once in the uploaded workbooks.");
                        }
                        if (cell(row, headers, "name of client").isBlank()
                                || cell(row, headers, "origin").isBlank() || cell(row, headers, "destination").isBlank()) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "MISSING_SHIPMENT_FIELDS", "AWB, customer, origin and destination must be populated.");
                        }
                        if (cell(row, headers, "date").isBlank()) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "MISSING_SHIPMENT_DATE", "The shipment date is required for the daily and monthly registers.");
                        }
                        if (cell(row, headers, "airline used").isBlank()) {
                            warnings = add(issues, warnings, filename, sheet.getSheetName(), r + 1, "WARNING",
                                    "MISSING_AIRLINE", "No airline is recorded for this airfreight movement.");
                        }
                        int issueStart = issues.size();
                        BigDecimal b = number(row, headers, "amount billed to client", filename, sheet.getSheetName(), issues);
                        BigDecimal c = number(row, headers, "amount paid by client", filename, sheet.getSheetName(), issues);
                        BigDecimal o = number(row, headers, "amount remaining", filename, sheet.getSheetName(), issues);
                        BigDecimal s = number(row, headers, "amount paid to supply", filename, sheet.getSheetName(), issues);
                        BigDecimal e = number(row, headers, "other expenses", filename, sheet.getSheetName(), issues);
                        BigDecimal net = number(row, headers, "net income", filename, sheet.getSheetName(), issues);
                        for (int j = issueStart; j < issues.size(); j++) {
                            if ("INVALID_NUMBER".equals(issues.get(j).code())) blockers++;
                        }
                        String supplierRaw = cell(row, headers, "amount paid to supply").trim();
                        String netRaw = cell(row, headers, "net income").trim();
                        // Treat unknown cost differently from a verified zero. Never invent profit.
                        if ("-".equals(supplierRaw) || supplierRaw.equalsIgnoreCase("N/A")) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "UNKNOWN_SUPPLIER_AMOUNT", "Supplier amount is a placeholder; confirm the real cost or explicitly enter zero.");
                        }
                        if (netRaw.startsWith("#") || formulaError(row, headers, "net income")) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "BROKEN_PROFIT_FORMULA", "The net-income cell contains a spreadsheet error; correct the source amounts.");
                        }
                        if (b == null || c == null) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "MISSING_BILLING", "Historical billed and collected amounts must be numeric; use zero for none.");
                        } else if (b.signum() < 0 || c.signum() < 0 || c.compareTo(b) > 0) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "INVALID_RECEIVABLE", "Collected amount must be between zero and the billed amount.");
                        } else {
                            BigDecimal expected = b.subtract(c);
                            if (o != null && o.subtract(expected).abs().compareTo(TOLERANCE) > 0) {
                                blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                        "RECEIVABLE_MISMATCH", "Remaining balance differs from billed minus collected.");
                            }
                            String status = cell(row, headers, "payment status").trim().toUpperCase(Locale.ROOT);
                            if (expected.signum() > 0 && "PAID".equals(status)) {
                                blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                        "FALSE_PAID_STATUS", "Payment status is Paid while an outstanding balance remains.");
                            } else if (expected.signum() == 0 && ("OUTSTANDING".equals(status)
                                    || "PARTIALLY PAID".equals(status))) {
                                warnings = add(issues, warnings, filename, sheet.getSheetName(), r + 1, "WARNING",
                                        "STALE_PAYMENT_STATUS", "Payment status disagrees with the zero outstanding balance.");
                            }
                            billed = billed.add(b); collected = collected.add(c); receivable = receivable.add(expected);
                            totals.billed = totals.billed.add(b);
                            totals.collected = totals.collected.add(c);
                        }
                        if (s != null) {
                            if (s.signum() < 0) {
                                blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                        "NEGATIVE_SUPPLIER_PAYMENT", "Supplier payments cannot be negative.");
                            } else {
                                if (s.signum() == 0 && b != null && b.signum() > 0) {
                                    warnings = add(issues, warnings, filename, sheet.getSheetName(), r + 1, "WARNING",
                                            "ZERO_SUPPLIER_PAYMENT", "No supplier payment is recorded; confirm whether cost is truly zero or unpaid/not entered.");
                                }
                                supplierPayments = supplierPayments.add(s);
                                totals.suppliers = totals.suppliers.add(s);
                            }
                        }
                        if (b != null && s != null && net != null && e != null
                                && net.subtract(b.subtract(s).subtract(e)).abs().compareTo(TOLERANCE) > 0) {
                            blockers = add(issues, blockers, filename, sheet.getSheetName(), r + 1, "BLOCKER",
                                    "PROFIT_MISMATCH", "Net income does not equal billed less supplier payments and other expenses.");
                        }
                    }
                }
                // Compare the finance summary with the matching operational month.
                for (Sheet sheet : workbook) {
                    String lower = sheet.getSheetName().toLowerCase(Locale.ROOT).trim();
                    if (!lower.endsWith(" finance")) continue;
                    String month = lower.substring(0, lower.length() - " finance".length()).trim();
                    if (!MONTHS.contains(month) || !monthly.containsKey(month)) continue;
                    Row header = sheet.getRow(0);
                    if (header == null || !cell(header, Map.of("title", 8), "title").toUpperCase(Locale.ROOT)
                            .contains("FINANCIAL SUMMARY")) continue;
                    Totals t = monthly.get(month);
                    BigDecimal financeBilled = numberAt(sheet, 1, 9);
                    BigDecimal financeCollected = numberAt(sheet, 2, 9);
                    BigDecimal financeSupplier = numberAt(sheet, 4, 9);
                    if ((financeBilled != null && financeBilled.subtract(t.billed).abs().compareTo(TOLERANCE) > 0)
                            || (financeCollected != null && financeCollected.subtract(t.collected).abs().compareTo(TOLERANCE) > 0)
                            || (financeSupplier != null && financeSupplier.subtract(t.suppliers).abs().compareTo(TOLERANCE) > 0)) {
                        blockers = add(issues, blockers, filename, sheet.getSheetName(), 1, "BLOCKER",
                                "FINANCE_SHEET_DISAGREEMENT", "Finance summary disagrees with the " + month
                                        + " AWB register. Resolve which source is authoritative before import.");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Unable to inspect workbook " + filename + ": " + e.getMessage(), e);
            }
        }
        if (!sawMothership) {
            warnings = add(issues, warnings, "", "", 0, "WARNING", "NO_MOTHERSHIP_REGISTER",
                    "No MOTHERSHIP monthly register was detected. Other supported AAL sheets may still be imported.");
        }
        return new Preview(rows, awbs.size(), billed, collected, receivable, supplierPayments,
                blockers, warnings, blockers == 0, List.copyOf(reviewed), List.copyOf(issues));
    }

    private static int add(List<Issue> issues, int count, String file, String sheet, int row,
            String severity, String code, String message) {
        if (issues.size() < MAX_ISSUES) issues.add(new Issue(file, sheet, row, severity, code, message));
        return count + 1;
    }

    private static Map<String, Integer> headers(Row row) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (row != null) for (Cell cell : row) {
            String key = normalize(new DataFormatter().formatCellValue(cell));
            if (!key.isBlank()) result.put(key, cell.getColumnIndex());
        }
        return result;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace('\u00a0', ' ').trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").replaceAll("[.:]+$", "");
    }

    private static String cell(Row row, Map<String, Integer> headers, String key) {
        Integer idx = headers.get(normalize(key));
        if (row == null || idx == null) return "";
        Cell c = row.getCell(idx);
        if (c == null) return "";
        DataFormatter f = new DataFormatter();
        if (c.getCellType() == CellType.FORMULA) {
            CellType cache = c.getCachedFormulaResultType();
            if (cache == CellType.ERROR) return "#ERROR";
            if (cache == CellType.STRING) return c.getStringCellValue().trim();
            if (cache == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue()).stripTrailingZeros().toPlainString();
            return "";
        }
        return f.formatCellValue(c).trim();
    }

    private static boolean formulaError(Row row, Map<String, Integer> headers, String key) {
        Integer idx = headers.get(normalize(key));
        Cell c = row == null || idx == null ? null : row.getCell(idx);
        return c != null && (c.getCellType() == CellType.ERROR
                || (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.ERROR));
    }

    private static BigDecimal number(Row row, Map<String, Integer> headers, String key,
            String filename, String sheet, List<Issue> issues) {
        String raw = cell(row, headers, key).replace(",", "").trim();
        if (raw.isBlank() || raw.equals("-") || raw.equalsIgnoreCase("N/A") || raw.startsWith("#")) return null;
        try { return new BigDecimal(raw); }
        catch (NumberFormatException e) {
            // Other validations will reject mandatory amounts; invalid optional columns
            // must also surface rather than being treated as a verified zero.
            if (issues.size() < MAX_ISSUES) issues.add(new Issue(filename, sheet, row.getRowNum() + 1,
                    "BLOCKER", "INVALID_NUMBER", "Invalid number in " + key + ": " + raw));
            return null;
        }
    }

    private static BigDecimal numberAt(Sheet sheet, int row, int col) {
        Row r = sheet.getRow(row);
        if (r == null || r.getCell(col) == null) return null;
        Cell c = r.getCell(col);
        if (c.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue());
        if (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC)
            return BigDecimal.valueOf(c.getNumericCellValue());
        try { return new BigDecimal(new DataFormatter().formatCellValue(c).replace(",", "")); }
        catch (Exception e) { return null; }
    }

    private static final class Totals {
        private BigDecimal billed = BigDecimal.ZERO;
        private BigDecimal collected = BigDecimal.ZERO;
        private BigDecimal suppliers = BigDecimal.ZERO;
    }
}
