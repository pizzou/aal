
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
public class BillingService {

    private final ShipmentRepository shipments;
    private final CommercialInvoiceRepository invoices;
    private final CommercialPaymentRepository payments;
    private final FinancePostingService finance;
    private final FinanceDocumentSequenceService documentSequences;

    public BillingService(
            ShipmentRepository shipments,
            CommercialInvoiceRepository invoices,
            CommercialPaymentRepository payments,
            FinancePostingService finance,
            FinanceDocumentSequenceService documentSequences) {

        this.shipments = shipments;
        this.invoices = invoices;
        this.payments = payments;
        this.finance = finance;
        this.documentSequences = documentSequences;
    }

    @Transactional
    public CommercialInvoice billShipment(
            UUID shipmentId,
            LocalDate dueDate,
            String owner) {

        UUID tenantId = TenantContext.getTenantId();

        Shipment shipment = shipments
                .findByIdAndTenantId(shipmentId, tenantId)
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
            return existing;
        }

        BigDecimal amount = shipment.getAmountBilledToClient();

        if (amount == null) {
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

        String invoiceNo = documentSequences.nextInvoiceNumber();

        CommercialInvoice invoice = invoices.saveAndFlush(
                new CommercialInvoice(
                        tenantId,
                        invoiceNo,
                        LocalDate.now(),
                        shipment.getClientName() != null
                                ? shipment.getClientName()
                                : shipment.getContact(),
                        shipmentId,
                        currency,
                        amount,
                        dueDate,
                        owner));

        finance.postInvoice(
                tenantId,
                invoice.getId(),
                amount,
                currency,
                invoiceNo);

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
                reference);
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
            String reference) {

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
                            reference)) {

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

        payments.saveAndFlush(
                new CommercialPayment(
                        tenantId,
                        invoice.getId(),
                        amount,
                        invoiceCurrency,
                        key,
                        normalizeReference(reference)));

        /*
         * Accounting is posted through the single finance boundary.
         */
        finance.postCustomerPayment(
                tenantId,
                invoice.getId(),
                amount,
                invoiceCurrency,
                invoice.getInvoiceNo(),
                reference);

        return invoices.save(invoice);
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
