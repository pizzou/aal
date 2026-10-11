
package com.logiplatform.service;

import com.logiplatform.dto.CommercialDtos.*;
import com.logiplatform.dto.CommandCenterShipmentDtos.UpdateRequest;
import com.logiplatform.dto.ShipmentDtos.CreateShipmentRequest;
import com.logiplatform.dto.ShipmentDtos.ShipmentResponse;
import com.logiplatform.dto.ShipmentDtos.UpdateStatusRequest;
import com.logiplatform.model.*;
import com.logiplatform.repository.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CommercialOperationsService {

    private final CommercialQuoteRepository quotes;
    private final CommercialInvoiceRepository invoices;
    private final ClientRecordRepository clients;
    private final PartnerRecordRepository partners;
    private final TaskRecordRepository tasks;
    private final ExpenseRecordRepository expenses;
    private final ShipmentRepository shipments;
    private final ShipmentService shipmentService;

    private final BillingService billing;
    private final FinancePostingService finance;
    private final QuoteLifecycleService quoteLifecycle;
    private final FinanceDocumentSequenceService documentSequences;
    private final org.springframework.jdbc.core.JdbcTemplate tenantDb;

    public CommercialOperationsService(
            CommercialQuoteRepository quotes,
            CommercialInvoiceRepository invoices,
            ClientRecordRepository clients,
            PartnerRecordRepository partners,
            TaskRecordRepository tasks,
            ExpenseRecordRepository expenses,
            ShipmentRepository shipments,
            ShipmentService shipmentService,
            BillingService billing,
            FinancePostingService finance,
            QuoteLifecycleService quoteLifecycle,
            FinanceDocumentSequenceService documentSequences,
            @Qualifier("tenantJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate tenantDb) {

        this.quotes = quotes;
        this.invoices = invoices;
        this.clients = clients;
        this.partners = partners;
        this.tasks = tasks;
        this.expenses = expenses;
        this.shipments = shipments;
        this.shipmentService = shipmentService;
        this.billing = billing;
        this.finance = finance;
        this.quoteLifecycle = quoteLifecycle;
        this.documentSequences = documentSequences;
        this.tenantDb = tenantDb;
    }

    @Transactional
    public QuoteResponse createQuote(QuoteRequest r) {

        UUID tenantId = TenantContext.getTenantId();
        if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quotation details are required");
        String quoteId = requiredText(r.quoteId(), "Quote ID", 80);
        String clientName = requiredText(r.client(), "Customer", 200);
        String route = requiredText(r.route(), "Route", 300);
        String serviceType = requiredText(r.serviceType(), "Service type", 80);
        if (r.quoteDate() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quote date is required");
        if (r.validUntil() != null && r.validUntil().isBefore(r.quoteDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quote expiry cannot be before the quote date");
        }

        if (quotes.findByTenantIdAndQuoteId(
                tenantId,
                quoteId).isPresent()) {

            throw conflict("Quote exists");
        }

        BigDecimal supplier = nz(r.supplierCost());

        BigDecimal other = nz(r.otherCost());

        BigDecimal amount = nz(r.quotedAmount());

        BigDecimal profit = amount.subtract(
                supplier.add(other));

        CommercialQuote quote = new CommercialQuote(
                tenantId,
                quoteId,
                r.quoteDate(),
                clientName,
                route,
                serviceType,
                normalizeText(r.commodity()),
                nz(r.chargeableWeightKg()),
                supplier,
                other,
                nz(r.markupPercent()),
                amount,
                profit,
                r.validUntil(),
                r.status() != null && "REQUESTED".equalsIgnoreCase(r.status().trim()) ? "REQUESTED" : "DRAFT",
                normalizeText(r.owner()),
                r.followUpDate(),
                normalizeText(r.notes()),
                r.pricingMode() == null || r.pricingMode().isBlank() ? "RULES_BASED" : r.pricingMode().trim().toUpperCase(Locale.ROOT));
        quote.setCustomerContact(normalizeEmail(r.customerEmail()), normalizeText(r.customerContactName()), normalizeText(r.customerPhone()));
        quote.applyCommercialTerms(r.currency(), r.incoterm(), r.taxRate(), r.taxAmount(), r.customsCost(), r.insuranceCost(), r.customerCreditTerms());
        CommercialQuote saved = quotes.save(quote);
        quoteLifecycle.ensureInitialVersion(saved.getId());
        return QuoteResponse.from(saved);
    }

    @Transactional
    public QuoteToShipmentResponse convertQuoteToShipment(
            UUID quoteId,
            String shipmentReference,
            String origin,
            String destination) {

        UUID tenantId = TenantContext.getTenantId();
        CommercialQuote quote = quotes.findById(quoteId)
                .filter(q -> tenantId.equals(q.getTenantId()))
                .orElseThrow(() -> notFound("Quote not found"));

        String[] route = quote.getRoute() == null
                ? new String[]{origin == null ? "" : origin, destination == null ? "" : destination}
                : quote.getRoute().split("\\s*(?:→|->|TO)\\s*", 2);
        String o = route.length > 0 && !route[0].isBlank()
                ? route[0].trim()
                : (origin == null ? "" : origin.trim());
        String d = route.length > 1 && !route[1].isBlank()
                ? route[1].trim()
                : (destination == null ? "" : destination.trim());

        if (o.isBlank() || d.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Origin and destination are required for shipment conversion");
        }

        TransportMode mode;
        try {
            mode = TransportMode.valueOf(
                    (quote.getServiceType() == null ? "ROAD" : quote.getServiceType())
                            .toUpperCase(Locale.ROOT)
                            .replace(" FREIGHT", "_FREIGHT")
                            .replace("SEA_FREIGHT", "SEA")
                            .replace("AIR_FREIGHT", "AIR")
                            .replace("ROAD_FREIGHT", "ROAD"));
        } catch (Exception e) {
            mode = TransportMode.ROAD;
        }

        BigDecimal governedAmount = quote.getLockedAmount() != null
                ? quote.getLockedAmount()
                : quote.getQuotedAmount();
        String governedCurrency = quote.getLockedCurrency() != null
                ? quote.getLockedCurrency()
                : (quote.getCurrency() == null ? "USD" : quote.getCurrency());

        if (governedAmount == null) {
            throw conflict("Quote has no governed price");
        }

        // Do not manufacture a shipment number here. The canonical ShipmentService
        // allocates the tenant/year sequence and, for AIR, the AAL house AWB.
        // This keeps quote conversion, public booking and direct shipment creation
        // on exactly the same numbering path.
        ShipmentResponse created = shipmentService.create(
                new CreateShipmentRequest(
                        null,
                        o,
                        d,
                        mode.name(),
                        null,
                        null));

        String notes = quote.getNotes();
        if (shipmentReference != null && !shipmentReference.isBlank()) {
            String customerReferenceNote = "Customer reference: " + shipmentReference.trim();
            notes = notes == null || notes.isBlank()
                    ? customerReferenceNote
                    : notes.trim() + " | " + customerReferenceNote;
        }

        ShipmentResponse enriched = shipmentService.updateCommandCenter(
                created.id(),
                new UpdateRequest(
                        quote.getClient(),
                        null,
                        quote.getCommodity(),
                        null,
                        o,
                        null,
                        d,
                        null,
                        null,
                        null,
                        null,
                        quote.getServiceType(),
                        null,
                        quote.getSupplierCost(),
                        quote.getOtherCost(),
                        governedAmount,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "UNPAID",
                        quote.getOwner(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        notes,
                        governedCurrency,
                        LocalDate.now(),
                        "BOOKED"));

        ShipmentResponse booked = shipmentService.updateStatus(
                enriched.id(),
                new UpdateStatusRequest("BOOKED"));

        quote.changeStatus("CONVERTED");
        quotes.save(quote);

        return new QuoteToShipmentResponse(
                quote.getId(),
                quote.getQuoteId(),
                booked.id(),
                booked.referenceCode(),
                "CONVERTED",
                "Quote converted to shipment");
    }

    @Transactional(readOnly = true)
    public List<QuoteResponse> listQuotes() {

        return quotes
                .findAllByTenantIdOrderByQuoteDateDesc(
                        TenantContext.getTenantId())
                .stream()
                .map(QuoteResponse::from)
                .toList();
    }

    @Transactional
    public QuoteResponse quoteStatus(
            UUID id,
            String status) {

        CommercialQuote quote = quotes.findById(id)
                .filter(x -> x.getTenantId()
                        .equals(
                                TenantContext
                                        .getTenantId()))
                .orElseThrow(() -> notFound(
                        "Quote not found"));

        if ("WON".equalsIgnoreCase(status) && quote.getPriceLockedAt() == null) {
            throw conflict("Quote price must be locked before acceptance");
        }
        quote.changeStatus(status);

        return QuoteResponse.from(
                quotes.save(quote));
    }

    /**
     * Commercial invoice creation remains available through the
     * commercial API, but the accounting side is now always posted
     * through FinancePostingService.
     */
    @Transactional
    public InvoiceResponse createInvoice(
            InvoiceRequest r) {

        UUID tenantId = TenantContext.getTenantId();

        String invoiceNo = (r.invoiceNo() == null || r.invoiceNo().isBlank())
                ? documentSequences.nextInvoiceNumber()
                : r.invoiceNo().trim();

        if (invoices.findByTenantIdAndInvoiceNo(tenantId, invoiceNo).isPresent()) {
            throw conflict("Invoice exists");
        }

        Shipment shipment = null;
        if (r.shipmentId() != null) {
            shipment = shipments
                    .findByIdAndTenantId(r.shipmentId(), tenantId)
                    .orElseThrow(() -> notFound("Shipment not found"));

            if (shipment.getAmountBilledToClient() != null
                    && shipment.getAmountBilledToClient().signum() > 0
                    && shipment.getAmountBilledToClient().compareTo(r.invoiceAmount()) != 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invoice amount must match the shipment's governed client revenue");
            }
        }

        String currency = normalizeCurrency(r.currency());
        if (shipment != null && shipment.getCurrency() != null
                && !shipment.getCurrency().equalsIgnoreCase(currency)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invoice currency must match the shipment currency");
        }

        CommercialInvoice invoice = invoices.save(
                new CommercialInvoice(
                        tenantId,
                        invoiceNo,
                        r.issueDate(),
                        r.client(),
                        r.shipmentId(),
                        currency,
                        r.invoiceAmount(),
                        r.dueDate(),
                        r.owner()));

        finance.postInvoice(
                tenantId,
                invoice.getId(),
                invoice.getInvoiceAmount(),
                invoice.getCurrency(),
                invoice.getInvoiceNo());
        tenantDb.update("""
                INSERT INTO commercial_invoice_lifecycle_history(id,tenant_id,invoice_id,from_status,to_status,reason,changed_by)
                VALUES(gen_random_uuid(),?,?,NULL,'ISSUED','Invoice created and issued through commercial operations',NULL)
                ON CONFLICT DO NOTHING
                """,tenantId,invoice.getId());

        return InvoiceResponse.from(invoice);
    }

    @Transactional
    public InvoiceResponse createInvoiceFromQuote(UUID quoteId, String invoiceNo, LocalDate dueDate) {
        UUID tenantId=TenantContext.getTenantId();
        CommercialQuote q=quotes.findById(quoteId).filter(x->tenantId.equals(x.getTenantId())).orElseThrow(()->notFound("Quote not found"));
        if(!"WON".equalsIgnoreCase(q.getStatus()) && !"CONVERTED".equalsIgnoreCase(q.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Quote must be accepted before invoicing");
        if (q.getPriceLockedAt() == null || q.getLockedAmount() == null || q.getAcceptedVersionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Quote must have an accepted locked version before invoicing");
        }
        String no=(invoiceNo==null||invoiceNo.isBlank())?documentSequences.nextInvoiceNumber():invoiceNo.trim();
        InvoiceResponse invoice = createInvoice(new InvoiceRequest(no,LocalDate.now(),q.getClient(),null,q.getLockedCurrency()==null?"USD":q.getLockedCurrency(),q.getLockedAmount(),dueDate,q.getOwner(),"Generated from accepted quote version "+q.getAcceptedVersionId()));
        tenantDb.update("UPDATE commercial_invoices SET quote_id=?,quote_version_id=?,governed_amount=?,governed_currency=? WHERE id=? AND tenant_id=?", q.getId(), q.getAcceptedVersionId(), q.getLockedAmount(), q.getLockedCurrency(), invoice.id(), TenantContext.getTenantId());
        tenantDb.update("INSERT INTO commercial_invoice_lines(id,tenant_id,invoice_id,line_no,charge_code,description,quantity,unit_price,amount,currency,source_type,source_reference) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(tenant_id,invoice_id,line_no) DO NOTHING", UUID.randomUUID(), TenantContext.getTenantId(), invoice.id(), 1, "FREIGHT", "Governed quotation " + q.getQuoteId(), BigDecimal.ONE, q.getLockedAmount(), q.getLockedAmount(), q.getLockedCurrency(), "QUOTE", q.getQuoteId());
        return invoice;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> listInvoices() {

        return invoices
                .findAllByTenantIdOrderByIssueDateDesc(
                        TenantContext.getTenantId())
                .stream()
                .map(InvoiceResponse::from)
                .toList();
    }

    /**
     * Payment rules are owned by BillingService.
     *
     * Therefore /api/commercial/invoices/{id}/payments and
     * /api/billing/invoices/{id}/payments cannot drift apart.
     */
    @Transactional
    public InvoiceResponse pay(
            UUID id,
            PaymentRequest r) {

        return InvoiceResponse.from(
                billing.recordPayment(
                        id,
                        r.amount(),
                        r.currency(),
                        r.idempotencyKey(),
                        r.reference(),
                        r.incomeSourceId()));
    }

    @Transactional
    public ClientResponse createClient(
            ClientRequest r) {

        UUID tenantId = TenantContext.getTenantId();
        if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer details are required");
        String clientId = requiredText(r.clientId(), "Client ID", 80);
        String company = requiredText(r.clientCompany(), "Company legal/business name", 200);
        String email = normalizeEmail(r.email());

        if (clients.findByTenantIdAndClientId(tenantId, clientId).isPresent()) throw conflict("Client ID already exists");
        if (clients.findFirstByTenantIdAndClientCompanyIgnoreCase(tenantId, company).isPresent()) throw conflict("Client company already exists");
        if (email != null && clients.findFirstByTenantIdAndEmailIgnoreCase(tenantId, email).isPresent()) throw conflict("Client email already exists");

        return ClientResponse.from(clients.save(new ClientRecord(
                tenantId, clientId, company, normalizeText(r.contactPerson()), normalizeText(r.phone()), email,
                normalizeText(r.industry()), normalizeText(r.country()), normalizeText(r.city()),
                normalizeText(r.leadSource()), normalizeStatus(r.clientStatus(), "ACTIVE"),
                normalizeText(r.relationshipOwner()), r.nextFollowUp(), normalizeText(r.notes()))));
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> clients() {

        return clients
                .findAllByTenantIdOrderByClientCompanyAsc(
                        TenantContext.getTenantId())
                .stream()
                .map(ClientResponse::from)
                .toList();
    }

    @Transactional
    public ClientResponse clientStatus(
            UUID id,
            String status) {

        ClientRecord client = clients.findById(id)
                .filter(x -> x.getTenantId()
                        .equals(
                                TenantContext
                                        .getTenantId()))
                .orElseThrow(() -> notFound(
                        "Client not found"));

        client.changeStatus(status);

        return ClientResponse.from(
                clients.save(client));
    }

    @Transactional
    public PartnerResponse createPartner(
            PartnerRequest r) {

        UUID tenantId = TenantContext.getTenantId();

        return PartnerResponse.from(
                partners.save(
                        new PartnerRecord(
                                tenantId,
                                r.partnerId(),
                                r.country(),
                                r.company(),
                                r.contactPerson(),
                                r.phone(),
                                r.email(),
                                r.services(),
                                r.cityPortAirport(),
                                r.paymentTerms(),
                                r.rating(),
                                r.status(),
                                r.lastVerified(),
                                r.notes())));
    }

    @Transactional(readOnly = true)
    public List<PartnerResponse> partners() {

        return partners
                .findAllByTenantIdOrderByCountryAscCompanyAsc(
                        TenantContext.getTenantId())
                .stream()
                .map(PartnerResponse::from)
                .toList();
    }

    @Transactional
    public TaskResponse createTask(
            TaskRequest r) {

        UUID tenantId = TenantContext.getTenantId();

        return TaskResponse.from(
                tasks.save(
                        new TaskRecord(
                                tenantId,
                                r.taskId(),
                                r.createdDate(),
                                r.department(),
                                r.relatedReference(),
                                r.task(),
                                r.priority(),
                                r.owner(),
                                r.dueDate(),
                                r.status(),
                                r.completionDate(),
                                r.notes())));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> tasks() {

        return tasks
                .findAllByTenantIdOrderByDueDateAsc(
                        TenantContext.getTenantId())
                .stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional
    public TaskResponse taskStatus(
            UUID id,
            String status) {

        TaskRecord task = tasks.findById(id)
                .filter(x -> x.getTenantId()
                        .equals(
                                TenantContext
                                        .getTenantId()))
                .orElseThrow(() -> notFound(
                        "Task not found"));

        task.changeStatus(status);

        return TaskResponse.from(
                tasks.save(task));
    }

    @Transactional
    public ExpenseResponse createExpense(ExpenseRequest r) {
        UUID tenantId = TenantContext.getTenantId();

        if (expenses.findByTenantIdAndExpenseId(tenantId, r.expenseId()).isPresent()) {
            throw conflict("Expense exists");
        }

        ExpenseRecord expense = expenses.saveAndFlush(new ExpenseRecord(
                tenantId, r.expenseId(), r.expenseDate(), r.type(), r.category(),
                r.shipmentId(), r.client(), r.vendorPayee(), r.description(),
                normalizeCurrency(r.currency()), r.originalAmount(), r.exchangeRateToUsd(),
                r.paymentMethod(), r.status(), r.approvedBy()));

        if ("APPROVED".equalsIgnoreCase(r.status()) ||
                (r.approvedBy() != null && !r.approvedBy().isBlank())) {
            finance.postExpense(tenantId, expense.getId(), expense.getOriginalAmount(),
                    expense.getCurrency(), expense.getExpenseId());
        }

        return ExpenseResponse.from(expense);
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> expenses() {

        return expenses
                .findAllByTenantIdOrderByExpenseDateDesc(
                        TenantContext.getTenantId())
                .stream()
                .map(ExpenseResponse::from)
                .toList();
    }

    private static String requiredText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must not exceed " + max + " characters");
        return normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeEmail(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeStatus(String value, String fallback) {
        String normalized = normalizeText(value);
        return normalized == null ? fallback : normalized.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal nz(
            BigDecimal value) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }

    private static String normalizeCurrency(
            String currency) {

        if (currency == null || currency.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Currency is required");
        }

        return currency
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private static ResponseStatusException notFound(
            String message) {

        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                message);
    }

    private static ResponseStatusException conflict(
            String message) {

        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message);
    }
}
