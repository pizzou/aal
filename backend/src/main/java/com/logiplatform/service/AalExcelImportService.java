
package com.logiplatform.service;

import com.logiplatform.model.AalImportBatch;
import com.logiplatform.model.ClientRecord;
import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.model.CommercialQuote;
import com.logiplatform.model.ExpenseRecord;
import com.logiplatform.model.PartnerRecord;
import com.logiplatform.model.Shipment;
import com.logiplatform.model.TaskRecord;
import com.logiplatform.model.TransportMode;
import com.logiplatform.repository.ClientRecordRepository;
import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.repository.CommercialQuoteRepository;
import com.logiplatform.repository.ExpenseRecordRepository;
import com.logiplatform.repository.PartnerRecordRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.repository.TaskRecordRepository;
import com.logiplatform.tenancy.TenantContext;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class AalExcelImportService {

        private static final Set<String> MONTHS = Set.of(
                        "January",
                        "February",
                        "March",
                        "April",
                        "May",
                        "June",
                        "July",
                        "August",
                        "September",
                        "October",
                        "November",
                        "December");

        private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
                        DateTimeFormatter.ofPattern("M/d/yyyy"),
                        DateTimeFormatter.ofPattern("M/d/yy"),
                        DateTimeFormatter.ofPattern("d/M/yyyy"),
                        DateTimeFormatter.ISO_LOCAL_DATE);

        private final ShipmentRepository shipments;
        private final CommercialQuoteRepository quotes;
        private final CommercialInvoiceRepository invoices;
        private final ClientRecordRepository clients;
        private final PartnerRecordRepository partners;
        private final TaskRecordRepository tasks;
        private final ExpenseRecordRepository expenses;
        private final FinancePostingService financePostingService;
        private final com.logiplatform.repository.AalImportBatchRepository importBatches;

        public AalExcelImportService(
                        ShipmentRepository shipments,
                        CommercialQuoteRepository quotes,
                        CommercialInvoiceRepository invoices,
                        ClientRecordRepository clients,
                        PartnerRecordRepository partners,
                        TaskRecordRepository tasks,
                        ExpenseRecordRepository expenses,
                        FinancePostingService financePostingService,
                        com.logiplatform.repository.AalImportBatchRepository importBatches) {

                this.shipments = shipments;
                this.quotes = quotes;
                this.invoices = invoices;
                this.clients = clients;
                this.partners = partners;
                this.tasks = tasks;
                this.expenses = expenses;
                this.financePostingService = financePostingService;
                this.importBatches = importBatches;
        }

        /**
         * Supports both AAL workbook structures:
         *
         * 1. AAL MOTHERSHIP
         * - January
         * - February
         * - ...
         * - December
         *
         * 2. AAL COMMAND CENTER
         * - Shipments
         * - Quotations
         * - Invoices
         * - Clients
         * - Partners
         * - Tasks
         * - Expenses
         *
         * .xlsx and .xlsm are supported through Apache POI.
         */
        @Transactional
        public ImportResult importWorkbook(MultipartFile file) {
                validateWorkbook(file);

                UUID tenant = TenantContext.getTenantId();
                if (tenant == null) {
                        throw new IllegalStateException("No tenant context is available");
                }

                final byte[] bytes;
                try {
                        bytes = file.getBytes();
                } catch (Exception e) {
                        throw new IllegalArgumentException("Unable to read AAL workbook", e);
                }

                String sha256 = sha256(bytes);
                Optional<AalImportBatch> previous = importBatches.findByTenantIdAndSourceSha256(tenant, sha256);
                if (previous.isPresent() && "COMPLETED".equalsIgnoreCase(previous.get().getStatus())) {
                        return toResult(previous.get().counts());
                }

                AalImportBatch batch = previous.orElseGet(() ->
                        importBatches.save(new AalImportBatch(tenant,
                                Optional.ofNullable(file.getOriginalFilename()).orElse("AAL-workbook"), sha256)));

                try (InputStream input = new java.io.ByteArrayInputStream(bytes);
                     Workbook workbook = WorkbookFactory.create(input)) {

                        // Two-pass import is intentional. Shipments must exist before invoices
                        // are linked, regardless of workbook sheet ordering.
                        List<Sheet> shipmentSheets = new ArrayList<>();
                        List<Sheet> commercialSheets = new ArrayList<>();
                        List<Sheet> supportingSheets = new ArrayList<>();
                        for (Sheet sheet : workbook) {
                                String name = sheet.getSheetName().trim();
                                if (name.equalsIgnoreCase("Shipments") || MONTHS.contains(name)) shipmentSheets.add(sheet);
                                else if (name.equalsIgnoreCase("Quotations") || name.equalsIgnoreCase("Invoices")) commercialSheets.add(sheet);
                                else if (name.equalsIgnoreCase("Clients") || name.equalsIgnoreCase("Partners") || name.equalsIgnoreCase("Tasks") || name.equalsIgnoreCase("Expenses")) supportingSheets.add(sheet);
                        }

                        int shipments = 0, quotes = 0, invoices = 0, clients = 0, partners = 0, tasks = 0, expenses = 0;
                        for (Sheet sheet : shipmentSheets) shipments += importShipments(sheet, tenant);
                        for (Sheet sheet : supportingSheets) {
                                String name = sheet.getSheetName().trim();
                                if (name.equalsIgnoreCase("Clients")) clients += importClients(sheet, tenant);
                                else if (name.equalsIgnoreCase("Partners")) partners += importPartners(sheet, tenant);
                                else if (name.equalsIgnoreCase("Tasks")) tasks += importTasks(sheet, tenant);
                                else if (name.equalsIgnoreCase("Expenses")) expenses += importExpenses(sheet, tenant);
                        }
                        for (Sheet sheet : commercialSheets) {
                                String name = sheet.getSheetName().trim();
                                if (name.equalsIgnoreCase("Quotations")) quotes += importQuotes(sheet, tenant);
                                else if (name.equalsIgnoreCase("Invoices")) invoices += importInvoices(sheet, tenant);
                        }

                        AalImportBatch.AalImportCounts counts = new AalImportBatch.AalImportCounts(
                                shipments, quotes, invoices, clients, partners, tasks, expenses);
                        batch.complete(counts);
                        importBatches.save(batch);
                        return toResult(counts);
                } catch (Exception e) {
                        batch.fail();
                        importBatches.save(batch);
                        throw new IllegalArgumentException("Unable to import AAL workbook: " + rootMessage(e), e);
                }
        }

        private static void validateWorkbook(MultipartFile file) {
                if (file == null || file.isEmpty()) throw new IllegalArgumentException("Excel file is empty");
                if (file.getSize() > 10L * 1024L * 1024L) throw new IllegalArgumentException("Excel file exceeds the 10 MB import limit");
                String filename = file.getOriginalFilename();
                if (filename == null || !(filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") || filename.toLowerCase(Locale.ROOT).endsWith(".xlsm")))
                        throw new IllegalArgumentException("Only .xlsx and .xlsm AAL workbooks are supported");
        }

        private static String sha256(byte[] bytes) {
                try {
                        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                        StringBuilder out = new StringBuilder(64);
                        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b));
                        return out.toString();
                } catch (Exception e) {
                        throw new IllegalStateException("SHA-256 is unavailable", e);
                }
        }

        private static ImportResult toResult(AalImportBatch.AalImportCounts c) {
                return new ImportResult(c.shipments(), c.quotations(), c.invoices(), c.clients(), c.partners(), c.tasks(), c.expenses());
        }

        private int importShipments(
                        Sheet sheet,
                        UUID tenant) {

                boolean monthly = MONTHS.contains(
                                sheet.getSheetName().trim());

                int headerRow = monthly
                                ? 0
                                : 2;

                int firstDataRow = monthly
                                ? 1
                                : 3;

                if (sheet.getLastRowNum() < firstDataRow) {
                        return 0;
                }

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(headerRow));

                if (monthly) {

                        requireHeaders(
                                        headerMap,
                                        "AWB NO",
                                        "ORIGIN",
                                        "DESTINATION");

                } else {

                        requireHeaders(
                                        headerMap,
                                        "Shipment ID",
                                        "Origin City / Port",
                                        "Destination City / Port");
                }

                int count = 0;

                for (int rowIndex = firstDataRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String reference = normalizeKey(value(row, headerMap, monthly ? "AWB NO" : "Shipment ID"));

                        if (blank(reference)) {
                                continue;
                        }

                        String origin = value(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "ORIGIN"
                                                        : "Origin City / Port");

                        String destination = value(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "DESTINATION"
                                                        : "Destination City / Port");

                        Shipment shipment = shipments
                                        .findByTenantIdAndReferenceCode(
                                                        tenant,
                                                        reference)
                                        .orElse(null);

                        if (shipment == null) {

                                shipment = new Shipment(
                                                tenant,
                                                reference,
                                                blank(origin)
                                                                ? "UNKNOWN"
                                                                : origin,
                                                blank(destination)
                                                                ? "UNKNOWN"
                                                                : destination,
                                                parseMode(
                                                                monthly
                                                                                ? "AIR"
                                                                                : value(
                                                                                                row,
                                                                                                headerMap,
                                                                                                "Service Type")),
                                                value(
                                                                row,
                                                                headerMap,
                                                                monthly
                                                                                ? "AIRLINE USED"
                                                                                : "Airline / Carrier"),
                                                reference);
                        }

                        BigDecimal gross = decimal(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "GROSS WEIGHT (KG)"
                                                        : "Actual Weight (kg)");

                        BigDecimal volume = decimal(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "VOLUMETRIC WEIGHT (KG)"
                                                        : "Volume Weight (kg)");

                        String owner = value(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "OPERATOR"
                                                        : "Owner");

                        BigDecimal supplierCost = nz(
                                        decimal(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? "AMOUNT PAID TO SUPPLY"
                                                                        : "Supplier Cost (USD)"));

                        /*
                         * Only the MOTHERSHIP workbook contains cumulative supplier
                         * cash paid.
                         *
                         * The Command Center's Supplier Cost is a cost basis,
                         * not a cash payment, and must never be posted as one.
                         */
                        BigDecimal supplierPaid = monthly
                                        ? nz(decimal(
                                                        row,
                                                        headerMap,
                                                        "AMOUNT PAID TO SUPPLY"))
                                        : BigDecimal.ZERO;

                        BigDecimal otherExpenses = decimal(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "OTHER EXPENSES"
                                                        : "Other Cost (USD)");

                        BigDecimal billed = decimal(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "AMOUNT BILLED TO CLIENT"
                                                        : "Client Revenue (USD)");

                        BigDecimal paid = decimal(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "AMOUNT PAID BY CLIENT"
                                                        : "");

                        String paymentStatus = value(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "PAYMENT STATUS"
                                                        : "Payment Status");

                        /*
                         * Some MOTHERSHIP rows contain formula-driven payment status.
                         *
                         * If the cached formula result is unavailable, derive it from
                         * the actual financial values.
                         */
                        if (monthly && blank(paymentStatus)) {

                                paymentStatus = derivePaymentStatus(
                                                billed,
                                                paid);
                        }

                        BigDecimal previousSupplierPaid = shipment.getAmountPaidToSupply() == null
                                        ? BigDecimal.ZERO
                                        : shipment.getAmountPaidToSupply();

                        shipment.updateCommandCenterFields(

                                        value(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? "NAME OF CLIENT"
                                                                        : "Client"),

                                        value(
                                                        row,
                                                        headerMap,
                                                        "Contact"),

                                        value(
                                                        row,
                                                        headerMap,
                                                        "COMMODITY"),

                                        monthly
                                                        ? ""
                                                        : value(
                                                                        row,
                                                                        headerMap,
                                                                        "Origin Country"),

                                        origin,

                                        monthly
                                                        ? ""
                                                        : value(
                                                                        row,
                                                                        headerMap,
                                                                        "Destination Country"),

                                        destination,

                                        gross,

                                        volume,

                                        intValue(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? null
                                                                        : "Packages"),

                                        value(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? "AIRLINE USED"
                                                                        : "Airline / Carrier"),

                                        value(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? "SERVICE TYPE"
                                                                        : "Service Type"),

                                        owner,

                                        supplierCost,

                                        monthly
                                                        ? BigDecimal.ZERO
                                                        : decimal(
                                                                        row,
                                                                        headerMap,
                                                                        "Other Cost (USD)"),

                                        billed,

                                        monthly
                                                        ? paid
                                                        : BigDecimal.ZERO,

                                        supplierPaid,

                                        otherExpenses,

                                        paymentStatus,

                                        owner,

                                        monthly
                                                        ? reference
                                                        : value(
                                                                        row,
                                                                        headerMap,
                                                                        "Invoice No."),

                                        toInstant(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? "DATE"
                                                                        : "ETD"),

                                        monthly
                                                        ? null
                                                        : toInstant(
                                                                        row,
                                                                        headerMap,
                                                                        "ETA"),

                                        monthly
                                                        ? ""
                                                        : value(
                                                                        row,
                                                                        headerMap,
                                                                        "Next Action"),

                                        monthly
                                                        ? null
                                                        : localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Next Action Date"),

                                        monthly
                                                        ? "Imported from AAL MOTHERSHIP"
                                                        : value(
                                                                        row,
                                                                        headerMap,
                                                                        "Notes"),

                                        monthly
                                                        ? "USD"
                                                        : defaultCurrency(
                                                                        value(
                                                                                        row,
                                                                                        headerMap,
                                                                                        "Currency")));

                        /*
                         * Date Opened
                         */
                        LocalDate opened = localDate(
                                        row,
                                        headerMap,
                                        monthly
                                                        ? "DATE"
                                                        : "Date Opened");

                        if (opened != null) {
                                shipment.setDateOpened(opened);
                        }

                        /*
                         * Workbook status.
                         */
                        shipment.setOperationalStatus(
                                        value(
                                                        row,
                                                        headerMap,
                                                        monthly
                                                                        ? "SHIPMENT STATUS"
                                                                        : "Status"));

                        if (supplierPaid.compareTo(previousSupplierPaid) < 0) {

                                throw new IllegalArgumentException(
                                                "Supplier payment cannot decrease for shipment "
                                                                + reference
                                                                + " during workbook import");
                        }

                        Shipment saved = shipments.save(shipment);

                        if (supplierPaid.compareTo(previousSupplierPaid) > 0) {

                                financePostingService.postSupplierPayment(
                                                tenant,
                                                saved.getId(),
                                                supplierPaid,
                                                saved.getCurrency(),
                                                saved.getReferenceCode());
                        }

                        count++;
                }

                return count;
        }

        private int importQuotes(
                        Sheet sheet,
                        UUID tenant) {

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(2));

                int count = 0;

                for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String quoteId = normalizeKey(value(row, headerMap, "Quote ID"));

                        if (blank(quoteId)
                                        || quotes
                                                        .findByTenantIdAndQuoteId(
                                                                        tenant,
                                                                        quoteId)
                                                        .isPresent()) {

                                continue;
                        }

                        BigDecimal supplier = nz(
                                        decimal(
                                                        row,
                                                        headerMap,
                                                        "Supplier Cost (USD)"));

                        BigDecimal other = nz(
                                        decimal(
                                                        row,
                                                        headerMap,
                                                        "Other Cost (USD)"));

                        BigDecimal markup = nz(
                                        decimal(
                                                        row,
                                                        headerMap,
                                                        "Markup %"));

                        BigDecimal quoted = decimal(
                                        row,
                                        headerMap,
                                        "Quoted Amount (USD)");

                        if (quoted == null) {

                                quoted = supplier
                                                .add(other)
                                                .multiply(
                                                                BigDecimal.ONE.add(
                                                                                markup.divide(
                                                                                                BigDecimal.valueOf(100),
                                                                                                8,
                                                                                                java.math.RoundingMode.HALF_UP)));
                        }

                        BigDecimal expectedProfit = decimal(
                                        row,
                                        headerMap,
                                        "Expected Profit (USD)");

                        if (expectedProfit == null) {

                                expectedProfit = quoted
                                                .subtract(supplier)
                                                .subtract(other);
                        }

                        quotes.save(
                                        new CommercialQuote(
                                                        tenant,
                                                        quoteId,
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Quote Date"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Client"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Route"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Service Type"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Commodity"),
                                                        decimal(
                                                                        row,
                                                                        headerMap,
                                                                        "Chargeable Weight (kg)"),
                                                        supplier,
                                                        other,
                                                        markup,
                                                        quoted,
                                                        expectedProfit,
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Valid Until"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Status"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Owner"),
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Follow-up Date"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Notes"),
                                                        "IMPORTED"));

                        count++;
                }

                return count;
        }

        private int importInvoices(
                        Sheet sheet,
                        UUID tenant) {

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(2));

                int count = 0;

                for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String invoiceNo = normalizeKey(value(row, headerMap, "Invoice No."));

                        /*
                         * Invoice records are identified by invoice number.
                         *
                         * DO NOT check ExpenseRecord here.
                         * expenseId belongs to importExpenses(), not importInvoices().
                         */
                        if (blank(invoiceNo)
                                        || invoices
                                                        .findByTenantIdAndInvoiceNo(
                                                                        tenant,
                                                                        invoiceNo)
                                                        .isPresent()) {

                                continue;
                        }

                        UUID shipmentId = null;

                        String shipmentReference = value(
                                        row,
                                        headerMap,
                                        "Shipment ID");

                        if (!blank(shipmentReference)) {

                                shipmentId = shipments
                                                .findByTenantIdAndReferenceCode(
                                                                tenant,
                                                                shipmentReference)
                                                .map(Shipment::getId)
                                                .orElse(null);
                        }

                        BigDecimal invoiceAmount = nz(
                                        decimal(
                                                        row,
                                                        headerMap,
                                                        "Invoice Amount"));

                        CommercialInvoice invoice = new CommercialInvoice(
                                        tenant,
                                        invoiceNo,
                                        localDate(
                                                        row,
                                                        headerMap,
                                                        "Issue Date"),
                                        value(
                                                        row,
                                                        headerMap,
                                                        "Client"),
                                        shipmentId,
                                        defaultCurrency(
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Currency")),
                                        invoiceAmount,
                                        localDate(
                                                        row,
                                                        headerMap,
                                                        "Due Date"),
                                        value(
                                                        row,
                                                        headerMap,
                                                        "Owner"));

                        invoice.setImportedCollectionData(
                                        decimal(
                                                        row,
                                                        headerMap,
                                                        "Amount Paid"),
                                        localDate(
                                                        row,
                                                        headerMap,
                                                        "Last Follow-up"),
                                        localDate(
                                                        row,
                                                        headerMap,
                                                        "Next Follow-up"),
                                        value(
                                                        row,
                                                        headerMap,
                                                        "Notes"));

                        invoices.save(invoice);

                        count++;
                }

                return count;
        }

        private int importClients(
                        Sheet sheet,
                        UUID tenant) {

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(2));

                int count = 0;

                for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String clientId = value(
                                        row,
                                        headerMap,
                                        "Client ID");

                        String company = value(row, headerMap, "Client / Company");
                        if (blank(clientId) || clients.findByTenantIdAndClientId(tenant, clientId).isPresent()) {
                                continue;
                        }
                        if (!blank(company) && clients.findFirstByTenantIdAndClientCompanyIgnoreCase(tenant, company).isPresent()) {
                                continue;
                        }

                        clients.save(
                                        new ClientRecord(
                                                        tenant,
                                                        clientId,
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Client / Company"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Contact Person"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Phone"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Email"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Industry"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Country"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "City"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Lead Source"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Client Status"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Relationship Owner"),
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Next Follow-up"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Notes")));

                        count++;
                }

                return count;
        }

        private int importPartners(
                        Sheet sheet,
                        UUID tenant) {

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(2));

                int count = 0;

                for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String partnerId = value(
                                        row,
                                        headerMap,
                                        "Partner ID");

                        if (blank(partnerId)) {
                                continue;
                        }

                        if (partners
                                        .findByTenantIdAndPartnerId(
                                                        tenant,
                                                        partnerId)
                                        .isPresent()) {

                                continue;
                        }

                        partners.save(
                                        new PartnerRecord(
                                                        tenant,
                                                        partnerId,
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Country"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Company"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Contact Person"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Phone"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Email"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Services"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "City / Port / Airport"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Payment Terms"),
                                                        intValue(
                                                                        row,
                                                                        headerMap,
                                                                        "Rating (1-5)"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Status"),
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Last Verified"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Notes")));

                        count++;
                }

                return count;
        }

        private int importTasks(
                        Sheet sheet,
                        UUID tenant) {

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(2));

                int count = 0;

                for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String taskId = value(
                                        row,
                                        headerMap,
                                        "Task ID");

                        if (blank(taskId)) {
                                continue;
                        }

                        if (tasks
                                        .findByTenantIdAndTaskId(
                                                        tenant,
                                                        taskId)
                                        .isPresent()) {

                                continue;
                        }

                        LocalDate created = localDate(
                                        row,
                                        headerMap,
                                        "Created Date");

                        tasks.save(
                                        new TaskRecord(
                                                        tenant,
                                                        taskId,
                                                        created == null
                                                                        ? LocalDate.now()
                                                                        : created,
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Department"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Related Shipment / Client"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Task"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Priority"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Owner"),
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Due Date"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Status"),
                                                        localDate(
                                                                        row,
                                                                        headerMap,
                                                                        "Completion Date"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Notes")));

                        count++;
                }

                return count;
        }

        private int importExpenses(
                        Sheet sheet,
                        UUID tenant) {

                Map<String, Integer> headerMap = headers(
                                sheet.getRow(2));

                int count = 0;

                for (int rowIndex = 3; rowIndex <= sheet.getLastRowNum(); rowIndex++) {

                        Row row = sheet.getRow(rowIndex);

                        if (row == null) {
                                continue;
                        }

                        String expenseId = value(
                                        row,
                                        headerMap,
                                        "Expense ID");

                        if (blank(expenseId)) {
                                continue;
                        }

                        /*
                         * expenseId belongs to this method and is therefore valid here.
                         */
                        if (expenses
                                        .findByTenantIdAndExpenseId(
                                                        tenant,
                                                        expenseId)
                                        .isPresent()) {

                                continue;
                        }

                        UUID shipmentId = null;

                        String shipmentReference = value(
                                        row,
                                        headerMap,
                                        "Shipment ID");

                        if (!blank(shipmentReference)) {

                                shipmentId = shipments
                                                .findByTenantIdAndReferenceCode(
                                                                tenant,
                                                                shipmentReference)
                                                .map(Shipment::getId)
                                                .orElse(null);
                        }

                        LocalDate expenseDate = localDate(
                                        row,
                                        headerMap,
                                        "Date");

                        expenses.save(
                                        new ExpenseRecord(
                                                        tenant,
                                                        expenseId,
                                                        expenseDate == null
                                                                        ? LocalDate.now()
                                                                        : expenseDate,
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Type"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Category"),
                                                        shipmentId,
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Client"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Vendor / Payee"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Description"),
                                                        defaultCurrency(
                                                                        value(
                                                                                        row,
                                                                                        headerMap,
                                                                                        "Currency")),
                                                        nz(
                                                                        decimal(
                                                                                        row,
                                                                                        headerMap,
                                                                                        "Original Amount")),
                                                        decimal(
                                                                        row,
                                                                        headerMap,
                                                                        "Exchange Rate to USD"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Payment Method"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Status"),
                                                        value(
                                                                        row,
                                                                        headerMap,
                                                                        "Approved By")));

                        count++;
                }

                return count;
        }

        private static String normalizeKey(String value) {
                if (value == null) return "";
                return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        }

        private static void requireHeaders(
                        Map<String, Integer> headers,
                        String... required) {

                for (String header : required) {

                        String key = header
                                        .trim()
                                        .toLowerCase(Locale.ROOT);

                        if (!headers.containsKey(key)) {

                                throw new IllegalArgumentException(
                                                "Required AAL workbook column is missing: "
                                                                + header);
                        }
                }
        }

        private static Map<String, Integer> headers(
                        Row row) {

                Map<String, Integer> result = new HashMap<>();

                if (row == null) {
                        return result;
                }

                DataFormatter formatter = new DataFormatter();

                for (Cell cell : row) {

                        String key = formatter
                                        .formatCellValue(cell)
                                        .trim()
                                        .toLowerCase(Locale.ROOT);

                        if (!key.isBlank()) {

                                result.put(
                                                key,
                                                cell.getColumnIndex());
                        }
                }

                return result;
        }

        private static String value(
                        Row row,
                        Map<String, Integer> headers,
                        String header) {

                if (row == null
                                || header == null
                                || header.isBlank()) {

                        return "";
                }

                Integer index = headers.get(
                                header
                                                .trim()
                                                .toLowerCase(Locale.ROOT));

                if (index == null) {
                        return "";
                }

                Cell cell = row.getCell(index);

                if (cell == null) {
                        return "";
                }

                DataFormatter formatter = new DataFormatter();
                if (cell.getCellType() == CellType.FORMULA) {
                        CellType cached = cell.getCachedFormulaResultType();
                        if (cached == CellType.NUMERIC) {
                                return formatter.formatRawCellContents(
                                                cell.getNumericCellValue(),
                                                cell.getCellStyle().getDataFormat(),
                                                cell.getCellStyle().getDataFormatString()).trim();
                        }
                        if (cached == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
                        if (cached == CellType.STRING) return cell.getStringCellValue().trim();
                        return "";
                }
                return formatter.formatCellValue(cell).trim();
        }

        private static BigDecimal decimal(
                        Row row,
                        Map<String, Integer> headers,
                        String header) {

                if (header == null) {
                        return null;
                }

                String raw = value(
                                row,
                                headers,
                                header)
                                .replace(",", "")
                                .trim();

                if (raw.isBlank()
                                || raw.equals("-")
                                || raw.equalsIgnoreCase("N/A")) {

                        return null;
                }

                try {

                        return new BigDecimal(raw);

                } catch (NumberFormatException e) {

                        return null;
                }
        }

        private static Integer intValue(
                        Row row,
                        Map<String, Integer> headers,
                        String header) {

                BigDecimal value = decimal(
                                row,
                                headers,
                                header);

                return value == null
                                ? null
                                : value.intValue();
        }

        /**
         * Correctly reads:
         *
         * 2/6/2026
         * 2/12/2026
         * Excel date cells
         * ISO timestamps
         */
        private static LocalDate localDate(
                        Row row,
                        Map<String, Integer> headers,
                        String header) {

                if (header == null) {
                        return null;
                }

                Integer index = headers.get(
                                header
                                                .trim()
                                                .toLowerCase(Locale.ROOT));

                if (index == null) {
                        return null;
                }

                Cell cell = row.getCell(index);

                if (cell == null) {
                        return null;
                }

                if ((cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA)
                                && DateUtil.isCellDateFormatted(cell)) {
                        if (cell.getCellType() == CellType.NUMERIC) {
                                return cell.getLocalDateTimeCellValue().toLocalDate();
                        }
                        if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                                return DateUtil.getJavaDate(cell.getNumericCellValue()).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
                        }
                }

                String raw = value(row, headers, header);

                if (raw.isBlank()) {
                        return null;
                }

                for (DateTimeFormatter formatter : DATE_FORMATS) {

                        try {

                                return LocalDate.parse(
                                                raw,
                                                formatter);

                        } catch (DateTimeParseException ignored) {
                        }
                }

                /*
                 * Support ISO timestamps.
                 */
                try {

                        return Instant
                                        .parse(raw)
                                        .atZone(ZoneOffset.UTC)
                                        .toLocalDate();

                } catch (DateTimeParseException ignored) {

                        return null;
                }
        }

        private static Instant toInstant(
                        Row row,
                        Map<String, Integer> headers,
                        String header) {

                LocalDate date = localDate(
                                row,
                                headers,
                                header);

                if (date == null) {
                        return null;
                }

                return date
                                .atStartOfDay(ZoneOffset.UTC)
                                .toInstant();
        }

        private static TransportMode parseMode(
                        String raw) {

                if (raw == null) {
                        return TransportMode.AIR;
                }

                String value = raw.toUpperCase(
                                Locale.ROOT);

                if (value.contains("AIR")) {
                        return TransportMode.AIR;
                }

                if (value.contains("SEA")
                                || value.contains("OCEAN")) {

                        return TransportMode.SEA;
                }

                if (value.contains("RAIL")) {
                        return TransportMode.RAIL;
                }

                return TransportMode.ROAD;
        }

        private static String defaultCurrency(
                        String value) {

                return blank(value)
                                ? "USD"
                                : value
                                                .trim()
                                                .toUpperCase(Locale.ROOT);
        }

        private static String derivePaymentStatus(
                        BigDecimal billed,
                        BigDecimal paid) {

                if (billed == null) {
                        return "";
                }

                BigDecimal actualPaid = paid == null
                                ? BigDecimal.ZERO
                                : paid;

                return billed.subtract(actualPaid).signum() == 0
                                ? "Paid"
                                : "Outstanding";
        }

        private static BigDecimal nz(
                        BigDecimal value) {

                return value == null
                                ? BigDecimal.ZERO
                                : value;
        }

        private static boolean blank(
                        String value) {

                return value == null
                                || value.isBlank();
        }

        private static String rootMessage(
                        Throwable throwable) {

                Throwable current = throwable;

                while (current.getCause() != null) {
                        current = current.getCause();
                }

                return current.getMessage() == null
                                ? current
                                                .getClass()
                                                .getSimpleName()
                                : current.getMessage();
        }

        public record ImportResult(
                        int shipments,
                        int quotations,
                        int invoices,
                        int clients,
                        int partners,
                        int tasks,
                        int expenses) {
        }
}
