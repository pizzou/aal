
package com.logiplatform.service;

import com.logiplatform.dto.CommercialDtos.*;
import com.logiplatform.model.*;
import com.logiplatform.repository.*;
import com.logiplatform.tenancy.TenantContext;
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
    private final ShipmentTrackingEventRepository trackingEvents;

    private final BillingService billing;
    private final FinancePostingService finance;
    private final QuoteLifecycleService quoteLifecycle;
    private final MilestoneOrchestrationService milestoneOrchestrationService;

    public CommercialOperationsService(
            CommercialQuoteRepository quotes,
            CommercialInvoiceRepository invoices,
            ClientRecordRepository clients,
            PartnerRecordRepository partners,
            TaskRecordRepository tasks,
            ExpenseRecordRepository expenses,
            ShipmentRepository shipments,
            ShipmentTrackingEventRepository trackingEvents,
            BillingService billing,
            FinancePostingService finance,
            QuoteLifecycleService quoteLifecycle,
            MilestoneOrchestrationService milestoneOrchestrationService) {

        this.quotes = quotes;
        this.invoices = invoices;
        this.clients = clients;
        this.partners = partners;
        this.tasks = tasks;
        this.expenses = expenses;
        this.shipments = shipments;
        this.trackingEvents = trackingEvents;
        this.billing = billing;
        this.finance = finance;
        this.quoteLifecycle = quoteLifecycle;
        this.milestoneOrchestrationService = milestoneOrchestrationService;
    }

    @Transactional
    public QuoteResponse createQuote(QuoteRequest r) {

        UUID tenantId = TenantContext.getTenantId();

        if (quotes.findByTenantIdAndQuoteId(
                tenantId,
                r.quoteId()).isPresent()) {

            throw conflict("Quote exists");
        }

        BigDecimal supplier = nz(r.supplierCost());

        BigDecimal other = nz(r.otherCost());

        BigDecimal amount = nz(r.quotedAmount());

        BigDecimal profit = amount.subtract(
                supplier.add(other));

        CommercialQuote quote = new CommercialQuote(
                tenantId,
                r.quoteId(),
                r.quoteDate(),
                r.client(),
                r.route(),
                r.serviceType(),
                r.commodity(),
                nz(r.chargeableWeightKg()),
                supplier,
                other,
                nz(r.markupPercent()),
                amount,
                profit,
                r.validUntil(),
                r.status() == null ? "Draft" : r.status(),
                r.owner(),
                r.followUpDate(),
                r.notes(),
                r.pricingMode() == null ? "RULES_BASED" : r.pricingMode());
        quote.setCustomerContact(r.customerEmail(), r.customerContactName(), r.customerPhone());
        quote.applyCommercialTerms(r.currency(), r.incoterm(), r.taxRate(), r.taxAmount(), r.customsCost(), r.insuranceCost(), r.customerCreditTerms());
        CommercialQuote saved = quotes.save(quote);
        quoteLifecycle.ensureInitialVersion(saved.getId());
        return QuoteResponse.from(saved);
    }

    @Transactional
    public QuoteToShipmentResponse convertQuoteToShipment(UUID quoteId, String shipmentReference, String origin, String destination) {
        UUID tenantId = TenantContext.getTenantId();
        CommercialQuote quote = quotes.findById(quoteId).filter(q -> tenantId.equals(q.getTenantId()))
                .orElseThrow(() -> notFound("Quote not found"));
        quote.changeStatus("WON");
        String ref = (shipmentReference == null || shipmentReference.isBlank()) ? "AAL-" + quote.getQuoteId() : shipmentReference.trim();
        if (shipments.existsByTenantIdAndReferenceCode(tenantId, ref)) throw conflict("Shipment reference already exists");
        String[] route = quote.getRoute() == null ? new String[]{origin == null ? "" : origin, destination == null ? "" : destination} : quote.getRoute().split("\\s*(?:→|->|TO)\\s*",2);
        String o = route.length > 0 && !route[0].isBlank() ? route[0].trim() : (origin == null ? "" : origin.trim());
        String d = route.length > 1 && !route[1].isBlank() ? route[1].trim() : (destination == null ? "" : destination.trim());
        if (o.isBlank() || d.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Origin and destination are required for shipment conversion");
        TransportMode mode;
        try { mode = TransportMode.valueOf((quote.getServiceType() == null ? "ROAD" : quote.getServiceType()).toUpperCase(Locale.ROOT).replace(" FREIGHT","_FREIGHT").replace("SEA_FREIGHT","SEA").replace("AIR_FREIGHT","AIR").replace("ROAD_FREIGHT","ROAD")); }
        catch (Exception e) { mode = TransportMode.ROAD; }
        BigDecimal governedAmount = quote.getLockedAmount() != null ? quote.getLockedAmount() : quote.getQuotedAmount();
        String governedCurrency = quote.getLockedCurrency() != null ? quote.getLockedCurrency() : (quote.getCurrency() == null ? "USD" : quote.getCurrency());
        if (governedAmount == null) throw conflict("Quote has no governed price");
        Shipment shipment = new Shipment(tenantId,ref,o,d,mode,null,null);
        shipment.updateCommandCenterFields(quote.getClient(),null,quote.getCommodity(),null,o,null,d,quote.getChargeableWeightKg(),null,null,null,quote.getServiceType(),null,quote.getSupplierCost(),quote.getOtherCost(),governedAmount,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"UNPAID",quote.getOwner(),null,null,null,null,null,quote.getNotes(),governedCurrency);
        Shipment saved=shipments.save(shipment);
        trackingEvents.save(new ShipmentTrackingEvent(
                tenantId,
                saved.getId(),
                TrackingEventType.BOOKED,
                o,
                "Shipment booked from quotation " + quote.getQuoteId(),
                java.time.Instant.now()));
        milestoneOrchestrationService.initialize(saved.getId(), mode.name(), o, d);
        quote.changeStatus("CONVERTED");
        quotes.save(quote);
        return new QuoteToShipmentResponse(quote.getId(),quote.getQuoteId(),saved.getId(),saved.getReferenceCode(),"CONVERTED","Quote converted to shipment");
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

        if (invoices.findByTenantIdAndInvoiceNo(
                tenantId,
                r.invoiceNo()).isPresent()) {

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
                        r.invoiceNo().trim(),
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

        return InvoiceResponse.from(invoice);
    }

    @Transactional
    public InvoiceResponse createInvoiceFromQuote(UUID quoteId, String invoiceNo, LocalDate dueDate) {
        UUID tenantId=TenantContext.getTenantId();
        CommercialQuote q=quotes.findById(quoteId).filter(x->tenantId.equals(x.getTenantId())).orElseThrow(()->notFound("Quote not found"));
        if(!"WON".equalsIgnoreCase(q.getStatus()) && !"CONVERTED".equalsIgnoreCase(q.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Quote must be accepted before invoicing");
        String no=(invoiceNo==null||invoiceNo.isBlank())?"AAL-INV-"+q.getQuoteId():invoiceNo.trim();
        return createInvoice(new InvoiceRequest(no,LocalDate.now(),q.getClient(),null,q.getLockedCurrency()==null?"USD":q.getLockedCurrency(),q.getLockedAmount()==null?q.getQuotedAmount():q.getLockedAmount(),dueDate,q.getOwner(),"Generated from accepted quote version "+(q.getAcceptedVersionId()==null?q.getQuoteId():q.getAcceptedVersionId())));
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
                        r.reference()));
    }

    @Transactional
    public ClientResponse createClient(
            ClientRequest r) {

        UUID tenantId = TenantContext.getTenantId();

        if (clients.findByTenantIdAndClientId(tenantId, r.clientId()).isPresent()) throw conflict("Client exists");
        if (r.clientCompany() != null && !r.clientCompany().isBlank() && clients.findFirstByTenantIdAndClientCompanyIgnoreCase(tenantId, r.clientCompany()).isPresent()) throw conflict("Client company already exists");
        if (r.email() != null && !r.email().isBlank() && clients.findFirstByTenantIdAndEmailIgnoreCase(tenantId, r.email()).isPresent()) throw conflict("Client email already exists");

        return ClientResponse.from(
                clients.save(
                        new ClientRecord(
                                tenantId,
                                r.clientId(),
                                r.clientCompany(),
                                r.contactPerson(),
                                r.phone(),
                                r.email(),
                                r.industry(),
                                r.country(),
                                r.city(),
                                r.leadSource(),
                                r.clientStatus(),
                                r.relationshipOwner(),
                                r.nextFollowUp(),
                                r.notes())));
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
