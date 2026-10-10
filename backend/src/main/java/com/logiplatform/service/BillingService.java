
package com.logiplatform.service;

import com.logiplatform.model.CommercialInvoice;
import com.logiplatform.model.CommercialPayment;
import com.logiplatform.model.Shipment;
import com.logiplatform.repository.CommercialInvoiceRepository;
import com.logiplatform.repository.CommercialPaymentRepository;
import com.logiplatform.repository.ShipmentRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.logiplatform.service.FinanceIncomeAllocationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import com.logiplatform.security.TenantPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class BillingService {

    private final ShipmentRepository shipments;
    private final CommercialInvoiceRepository invoices;
    private final CommercialPaymentRepository payments;
    private final FinancePostingService finance;
    private final FinanceDocumentSequenceService documentSequences;
    private final FinanceIncomeAllocationService incomeAllocations;
    private final FinancialHardeningService financialHardening;

    @PersistenceContext
    private EntityManager entityManager;

    public BillingService(
            ShipmentRepository shipments,
            CommercialInvoiceRepository invoices,
            CommercialPaymentRepository payments,
            FinancePostingService finance,
            FinanceDocumentSequenceService documentSequences,
            FinanceIncomeAllocationService incomeAllocations,
            FinancialHardeningService financialHardening) {

        this.shipments = shipments;
        this.invoices = invoices;
        this.payments = payments;
        this.finance = finance;
        this.documentSequences = documentSequences;
        this.incomeAllocations = incomeAllocations;
        this.financialHardening = financialHardening;
    }

    @Transactional
    public CommercialInvoice createDraftInvoice(UUID shipmentId, LocalDate dueDate, String owner) {
        return createDraftInvoice(shipmentId, dueDate, owner, null, null);
    }

    @Transactional
    public CommercialInvoice createDraftInvoice(UUID shipmentId, LocalDate dueDate, String owner,
            String taxJurisdictionCode, String taxCode) {
        UUID tenantId=TenantContext.getTenantId();
        if (dueDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice due date is required");
        }
        boolean hasJurisdiction = taxJurisdictionCode != null && !taxJurisdictionCode.isBlank();
        boolean hasTaxCode = taxCode != null && !taxCode.isBlank();
        if (hasJurisdiction != hasTaxCode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Both tax jurisdiction code and tax code are required when applying tax");
        }
        Shipment shipment=shipments.findLockedByIdAndTenantId(shipmentId,tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Shipment not found"));
        CommercialInvoice existing=invoices.findByTenantIdAndShipmentId(tenantId,shipmentId).orElse(null);
        if(existing!=null) {
            if (hasJurisdiction && "DRAFT".equalsIgnoreCase(existing.getLifecycleStatus())) {
                if (existing.getTaxCode() != null && !existing.getTaxCode().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "A tax rule is already applied to this draft invoice");
                }
                financialHardening.applyTaxToDraftInvoice(existing.getId(), taxJurisdictionCode, taxCode, LocalDate.now());
                entityManager.refresh(existing);
            }
            return existing;
        }

        BigDecimal amount = shipment.getAmountBilledToClient();
        // Some legacy/imported shipments persist zero in amountBilledToClient
        // while the governed client revenue is populated. Treat zero the same
        // as an absent billed amount so those legitimate shipments can invoice.
        if (amount == null || amount.signum() <= 0) amount = shipment.getClientRevenue();
        if(amount==null || amount.signum()<=0)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Shipment has no billable client revenue");

        String currency=normalizeCurrency(shipment.getCurrency()==null?"USD":shipment.getCurrency());
        String invoiceNo=shipment.getInvoiceNo();
        if(invoiceNo==null || invoiceNo.isBlank()){
            invoiceNo=documentSequences.nextInvoiceNumber();
            shipment.assignInvoiceNoIfBlank(invoiceNo);
            shipments.save(shipment);
        }

        CommercialInvoice invoice = new CommercialInvoice(
                tenantId, invoiceNo, LocalDate.now(),
                shipment.getClientName() != null ? shipment.getClientName() : shipment.getContact(),
                shipmentId, currency, amount, dueDate, owner);
        invoice.changeLifecycleStatus("DRAFT");
        invoice = invoices.saveAndFlush(invoice);
        invoices.insertLifecycleHistory(tenantId,invoice.getId(),null,"DRAFT","Draft invoice created",currentUser());
        if (hasJurisdiction) {
            financialHardening.applyTaxToDraftInvoice(invoice.getId(), taxJurisdictionCode, taxCode, LocalDate.now());
            entityManager.refresh(invoice);
        }
        return invoice;
    }

    @Transactional
    public CommercialInvoice billShipment(UUID shipmentId, LocalDate dueDate, String owner) {
        return billShipment(shipmentId, dueDate, owner, null, null);
    }

    @Transactional
    public CommercialInvoice billShipment(
            UUID shipmentId,
            LocalDate dueDate,
            String owner,
            String taxJurisdictionCode,
            String taxCode) {

        boolean hasJurisdiction = taxJurisdictionCode != null && !taxJurisdictionCode.isBlank();
        boolean hasTaxCode = taxCode != null && !taxCode.isBlank();
        if (hasJurisdiction != hasTaxCode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Both tax jurisdiction code and tax code are required when applying tax");
        }
        UUID tenantId = TenantContext.getTenantId();
        if (dueDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice due date is required");
        }

        // Serialize invoice creation/issue attempts for the same shipment. The
        // unique tenant+shipment constraint remains the final database guard,
        // while this row lock prevents concurrent requests from allocating
        // competing invoice numbers or posting the same receivable twice.
        Shipment shipment = shipments
                .findLockedByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Shipment not found"));

        /*
         * A shipment may only have one canonical commercial invoice.
         * Retrying the operation therefore returns the existing invoice
         * rather than generating another financial posting.
         */
        CommercialInvoice existing = invoices
                .findByTenantIdAndShipmentId(tenantId, shipmentId)
                .orElse(null);

        if (existing != null) {
            if ("DRAFT".equalsIgnoreCase(existing.getLifecycleStatus())) {
                if (hasJurisdiction) {
                    if (existing.getTaxCode() != null && !existing.getTaxCode().isBlank()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "A tax rule is already applied to this draft invoice");
                    }
                    financialHardening.applyTaxToDraftInvoice(existing.getId(), taxJurisdictionCode, taxCode, LocalDate.now());
                    entityManager.refresh(existing);
                }
                if (existing.getTaxAmount() != null && existing.getTaxAmount().signum() > 0)
                    finance.postInvoiceWithTax(tenantId, existing.getId(), existing.getInvoiceAmount(), existing.getTaxAmount(),
                            existing.getCurrency(), existing.getInvoiceNo());
                else finance.postInvoice(tenantId, existing.getId(), existing.getInvoiceAmount(),
                        existing.getCurrency(), existing.getInvoiceNo());
                existing.changeLifecycleStatus("ISSUED");
                invoices.updateLifecycleStatus(tenantId, existing.getId(), "ISSUED");
                invoices.insertLifecycleHistory(tenantId, existing.getId(), "DRAFT", "ISSUED",
                        "Draft invoice issued by billing", currentUser());
            }
            return existing;
        }

        BigDecimal amount = shipment.getAmountBilledToClient();
        // Zero is a common default for imported/older shipment records. If it
        // is not billable, fall back to the actual governed client revenue.
        if (amount == null || amount.signum() <= 0) {
            amount = shipment.getClientRevenue();
        }

        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Shipment has no billable client revenue");
        }

        String currency = normalizeCurrency(
                shipment.getCurrency() == null
                        ? "USD"
                        : shipment.getCurrency());

        String invoiceNo = shipment.getInvoiceNo();
        if (invoiceNo == null || invoiceNo.isBlank()) {
            invoiceNo = documentSequences.nextInvoiceNumber();
            shipment.assignInvoiceNoIfBlank(invoiceNo);
            shipments.save(shipment);
        }

        CommercialInvoice invoice = new CommercialInvoice(
                tenantId,
                invoiceNo,
                LocalDate.now(),
                shipment.getClientName() != null ? shipment.getClientName() : shipment.getContact(),
                shipmentId,
                currency,
                amount,
                dueDate,
                owner);
        // Persist every new invoice as a draft first. This makes tax application
        // part of the same transaction and ensures the immutable-issued-document
        // database guard never sees a financial edit after issuance.
        invoice.changeLifecycleStatus("DRAFT");
        invoice = invoices.saveAndFlush(invoice);
        invoices.insertLifecycleHistory(tenantId, invoice.getId(), null, "DRAFT",
                "Draft invoice created from shipment billing", currentUser());

        if (hasJurisdiction) {
            financialHardening.applyTaxToDraftInvoice(invoice.getId(), taxJurisdictionCode, taxCode, LocalDate.now());
            entityManager.refresh(invoice);
        }
        if (invoice.getTaxAmount() != null && invoice.getTaxAmount().signum() > 0)
            finance.postInvoiceWithTax(tenantId, invoice.getId(), invoice.getInvoiceAmount(), invoice.getTaxAmount(), currency, invoiceNo);
        else finance.postInvoice(tenantId, invoice.getId(), invoice.getInvoiceAmount(), currency, invoiceNo);
        invoice.changeLifecycleStatus("ISSUED");
        invoices.updateLifecycleStatus(tenantId, invoice.getId(), "ISSUED");
        invoices.insertLifecycleHistory(tenantId, invoice.getId(), "DRAFT", "ISSUED",
                "Invoice issued from shipment billing", currentUser());

        return invoice;
    }

    /**
     * Compatibility entry point used by the existing billing controller.
     *
     * The invoice currency remains authoritative.
     */
    @Transactional
    public CommercialInvoice recordPayment(
            UUID invoiceId,
            BigDecimal amount,
            String idempotencyKey,
            String reference) {

        return recordPayment(
                invoiceId,
                amount,
                null,
                idempotencyKey,
                reference,
                null);
    }

    /**
     * Backward-compatible canonical payment entry point for integrations that
     * do not provide income classification.
     */
    @Transactional
    public CommercialInvoice recordPayment(
            UUID invoiceId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String reference) {
        return recordPayment(
                invoiceId, amount, currency, idempotencyKey, reference, null);
    }

    /**
     * Canonical payment operation used by both:
     *
     * /api/billing
     * /api/commercial
     *
     * This prevents the two APIs from maintaining separate payment rules.
     */
    @Transactional
    public CommercialInvoice recordPayment(
            UUID invoiceId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String reference,
            UUID incomeSourceId) {

        UUID tenantId = TenantContext.getTenantId();

        String key = normalizeIdempotencyKey(idempotencyKey);

        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment amount must be greater than zero");
        }

        /*
         * Idempotency is checked before changing the invoice.
         */
        CommercialPayment prior = payments
                .findByTenantIdAndIdempotencyKey(
                        tenantId,
                        key)
                .orElse(null);

        if (prior != null) {

            CommercialInvoice priorInvoice = invoices
                    .findByTenantIdAndIdForUpdate(
                            tenantId,
                            prior.getInvoiceId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Payment references missing invoice"));

            String requestedCurrency = currency == null || currency.isBlank()
                    ? priorInvoice.getCurrency()
                    : normalizeCurrency(currency);

            if (!prior.getInvoiceId().equals(invoiceId)
                    || prior.getAmount().compareTo(amount) != 0
                    || !prior.getCurrency()
                            .equalsIgnoreCase(requestedCurrency)
                    || !sameNullable(
                            prior.getReference(),
                            reference)
                    || !java.util.Objects.equals(
                            prior.getIncomeSourceId(),
                            incomeSourceId)) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key was already used with different payment data");
            }

            /*
             * Safe retry: return the original invoice state.
             */
            return priorInvoice;
        }

        /*
         * Pessimistic invoice lock prevents concurrent payments from
         * independently spending the same remaining balance.
         */
        CommercialInvoice invoice = invoices
                .findByTenantIdAndIdForUpdate(
                        tenantId,
                        invoiceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Invoice not found"));

        String invoiceCurrency = normalizeCurrency(invoice.getCurrency());

        /*
         * Re-check idempotency after acquiring the invoice lock. Two identical
         * requests can pass the first read concurrently; the second request
         * must observe the committed payment instead of attempting a second
         * receipt/ledger posting.
         */
        CommercialPayment lockedPrior = payments
                .findByTenantIdAndIdempotencyKey(tenantId, key)
                .orElse(null);
        if (lockedPrior != null) {
            String requestedCurrency = currency == null || currency.isBlank()
                    ? invoiceCurrency
                    : normalizeCurrency(currency);
            if (!lockedPrior.getInvoiceId().equals(invoiceId)
                    || lockedPrior.getAmount().compareTo(amount) != 0
                    || !lockedPrior.getCurrency().equalsIgnoreCase(requestedCurrency)
                    || !sameNullable(lockedPrior.getReference(), reference)
                    || !java.util.Objects.equals(lockedPrior.getIncomeSourceId(), incomeSourceId)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key was already used with different payment data");
            }
            return invoice;
        }

        if (currency != null
                && !currency.isBlank()
                && !invoiceCurrency.equalsIgnoreCase(
                        currency.trim())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment currency does not match invoice currency");
        }

        /*
         * CommercialInvoice validates:
         * - positive payment
         * - payment cannot exceed remaining balance
         */
        invoice.applyPayment(amount);

        String receiptNo = documentSequences.nextReceiptNumber();

        CommercialPayment payment = payments.saveAndFlush(
                new CommercialPayment(
                        tenantId,
                        invoice.getId(),
                        amount,
                        invoiceCurrency,
                        key,
                        normalizeReference(reference),
                        incomeSourceId,
                        receiptNo));

        /*
         * Income allocation is metadata attached to the canonical payment.
         * No second income/payment ledger is created and no Excel values are
         * seeded. Active rules are copied as percentages so historical
         * allocation remains stable if configuration changes later.
         */
        incomeAllocations.applyRules(payment);

        /*
         * Accounting is posted through the single finance boundary.
         */
        finance.postCustomerPayment(
                tenantId,
                payment.getId(),
                invoice.getId(),
                amount,
                invoiceCurrency,
                invoice.getInvoiceNo(),
                reference);

        String previousLifecycle = savedLifecycle(invoice);
        CommercialInvoice saved = invoices.save(invoice);
        String lifecycle = saved.getBalance().signum() <= 0 ? "PAID" : "PARTIALLY_PAID";
        saved.changeLifecycleStatus(lifecycle);
        invoices.updateLifecycleStatus(tenantId, saved.getId(), lifecycle);
        if (!lifecycle.equalsIgnoreCase(previousLifecycle)) {
            invoices.insertLifecycleHistory(tenantId, saved.getId(), previousLifecycle, lifecycle,
                    "Customer payment recorded", currentUser());
        }
        return saved;
    }

    private static String savedLifecycle(CommercialInvoice invoice) {
        String value=invoice.getLifecycleStatus();
        return value==null || value.isBlank() ? "ISSUED" : value;
    }

    private static UUID currentUser() {
        Object principal=SecurityContextHolder.getContext().getAuthentication()==null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof TenantPrincipal tp ? tp.userId() : null;
    }

    private static String normalizeCurrency(String currency) {

        if (currency == null || currency.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Currency is required");
        }

        return currency
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private static String normalizeIdempotencyKey(String key) {

        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required");
        }

        String normalized = key.trim();

        if (normalized.length() > 255) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key exceeds 255 characters");
        }

        return normalized;
    }

    private static String normalizeReference(String reference) {

        if (reference == null) {
            return null;
        }

        String normalized = reference.trim();

        return normalized.isBlank()
                ? null
                : normalized;
    }

    private static boolean sameNullable(
            String left,
            String right) {

        String a = normalizeReference(left);
        String b = normalizeReference(right);

        return a == null
                ? b == null
                : a.equals(b);
    }
}
