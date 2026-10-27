
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

        /**
         * Preview using the tenant's configured default 18% VAT rule; the user does not
         * type tax codes.
         */
        @Transactional(readOnly = true)
        public java.util.Map<String, Object> previewDefaultShipmentInvoiceVat(UUID shipmentId, LocalDate onDate) {
                UUID tenantId = TenantContext.getTenantId();
                Shipment shipment = shipments.findByIdAndTenantId(shipmentId, tenantId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Shipment not found"));
                String currency = normalizeCurrency(
                                shipment.getCurrency() == null ? "USD" : shipment.getCurrency());
                java.util.Map<String, Object> rule = financialHardening.defaultInvoiceVatRule(onDate, currency);
                return previewShipmentInvoiceTax(shipmentId,
                                java.util.Objects.toString(rule.get("jurisdiction_code")),
                                java.util.Objects.toString(rule.get("code")), onDate);
        }

        /**
         * Server-authoritative invoice tax preview. It deliberately does not persist a
         * tax snapshot; the immutable snapshot is created only when the selected rule
         * is applied to the draft invoice.
         */
        @Transactional(readOnly = true)
        public java.util.Map<String, Object> previewShipmentInvoiceTax(
                        UUID shipmentId, String taxJurisdictionCode, String taxCode, LocalDate onDate) {
                UUID tenantId = TenantContext.getTenantId();
                if (taxJurisdictionCode == null || taxJurisdictionCode.isBlank()
                                || taxCode == null || taxCode.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Select a configured tax jurisdiction and tax code to preview VAT");
                }
                Shipment shipment = shipments.findByIdAndTenantId(shipmentId, tenantId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Shipment not found"));
                BigDecimal amount = shipment.getAmountBilledToClient();
                if (amount == null || amount.signum() <= 0)
                        amount = shipment.getClientRevenue();
                if (amount == null || amount.signum() <= 0) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "Shipment has no billable client revenue");
                }
                String currency = normalizeCurrency(shipment.getCurrency() == null ? "USD" : shipment.getCurrency());
                java.util.Map<String, Object> tax = financialHardening.calculateTax(
                                taxJurisdictionCode, taxCode, amount, currency, onDate, "INVOICE", null);
                if (n(tax.get("withholdingAmount")).signum() > 0) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "This tax rule includes withholding and cannot be used for invoice preview until withholding handling is configured");
                }
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                result.put("shipmentId", shipmentId);
                result.put("currency", currency);
                result.put("taxName", tax.get("taxName"));
                result.put("taxCode", tax.get("taxCode"));
                result.put("taxJurisdictionCode", tax.get("jurisdictionCode"));
                result.put("taxRate", tax.get("rate"));
                result.put("taxInclusive", tax.get("inclusive"));
                result.put("subtotalAmount", tax.get("taxableAmount"));
                result.put("taxAmount", tax.get("taxAmount"));
                result.put("invoiceAmount", tax.get("totalAmount"));
                result.put("effectiveDate", tax.get("effectiveDate"));
                result.put("taxRegistrationNo", tax.get("taxRegistrationNo"));
                return result;
        }

        private static BigDecimal n(Object value) {
                if (value instanceof BigDecimal decimal)
                        return decimal;
                if (value == null)
                        return BigDecimal.ZERO;
                return new BigDecimal(value.toString());
        }

        @Transactional
        public CommercialInvoice createDraftInvoice(UUID shipmentId, LocalDate dueDate, String owner) {
                return createDraftInvoice(shipmentId, dueDate, owner, null, null);
        }

        @Transactional
        public CommercialInvoice createDraftInvoice(UUID shipmentId, LocalDate dueDate, String owner,
                        String taxJurisdictionCode, String taxCode) {
                UUID tenantId = TenantContext.getTenantId();
                if (dueDate == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice due date is required");
                }
                boolean hasJurisdiction = taxJurisdictionCode != null && !taxJurisdictionCode.isBlank();
                boolean hasTaxCode = taxCode != null && !taxCode.isBlank();
                if (hasJurisdiction != hasTaxCode) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Both tax jurisdiction code and tax code are required when applying tax");
                }
                Shipment shipment = shipments.findLockedByIdAndTenantId(shipmentId, tenantId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Shipment not found"));
                CommercialInvoice existing = invoices.findByTenantIdAndShipmentId(tenantId, shipmentId).orElse(null);
                if (existing != null) {
                        if (hasJurisdiction && "DRAFT".equalsIgnoreCase(existing.getLifecycleStatus())) {
                                if (existing.getTaxCode() != null && !existing.getTaxCode().isBlank()) {
                                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                        "A tax rule is already applied to this draft invoice");
                                }
                                financialHardening.applyTaxToDraftInvoice(existing.getId(), taxJurisdictionCode,
                                                taxCode, LocalDate.now());
                                entityManager.refresh(existing);
                        }
                        return existing;
                }

                BigDecimal amount = shipment.getAmountBilledToClient();
                // Some legacy/imported shipments persist zero in amountBilledToClient
                // while the governed client revenue is populated. Treat zero the same
                // as an absent billed amount so those legitimate shipments can invoice.
                if (amount == null || amount.signum() <= 0)
                        amount = shipment.getClientRevenue();
                if (amount == null || amount.signum() <= 0)
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "Shipment has no billable client revenue");

                String currency = normalizeCurrency(shipment.getCurrency() == null ? "USD" : shipment.getCurrency());
                String invoiceNo = shipment.getInvoiceNo();
                if (invoiceNo == null || invoiceNo.isBlank()) {
                        invoiceNo = documentSequences.nextInvoiceNumber();
                        shipment.assignInvoiceNoIfBlank(invoiceNo);
                        shipments.save(shipment);
                }

                CommercialInvoice invoice = new CommercialInvoice(
                                tenantId, invoiceNo, LocalDate.now(),
                                shipment.getClientName() != null ? shipment.getClientName() : shipment.getContact(),
                                shipmentId, currency, amount, dueDate, owner);
                invoice.changeLifecycleStatus("DRAFT");
                invoice = invoices.saveAndFlush(invoice);
                invoices.insertLifecycleHistory(tenantId, invoice.getId(), null, "DRAFT", "Draft invoice created",
                                currentUser());
                if (hasJurisdiction) {
                        financialHardening.applyTaxToDraftInvoice(invoice.getId(), taxJurisdictionCode, taxCode,
                                        LocalDate.now());
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
                // A retry for a previously issued invoice must return the canonical
                // record even if the tax configuration has changed since issuance.
                if (existing != null && !"DRAFT".equalsIgnoreCase(existing.getLifecycleStatus())) {
                        return existing;
                }

                String currency = normalizeCurrency(
                                shipment.getCurrency() == null ? "USD" : shipment.getCurrency());
                java.util.Map<String, Object> defaultVatRule = financialHardening.defaultInvoiceVatRule(LocalDate.now(),
                                currency);
                String resolvedJurisdiction = java.util.Objects.toString(
                                defaultVatRule.get("jurisdiction_code"), "");
                String resolvedTaxCode = java.util.Objects.toString(defaultVatRule.get("code"), "");
                // Request-body tax codes are retained only for compatibility with older
                // clients. They cannot override the tenant's server-selected tax rule.
                if (hasJurisdiction &&
                                (!resolvedJurisdiction.equalsIgnoreCase(taxJurisdictionCode.trim())
                                                || !resolvedTaxCode.equalsIgnoreCase(taxCode.trim()))) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "The applicable VAT rule changed or the submitted rule is not the tenant default. Refresh the invoice tax preview and retry.");
                }

                if (existing != null) {
                        if ("DRAFT".equalsIgnoreCase(existing.getLifecycleStatus())) {
                                String existingTaxCode = existing.getTaxCode();
                                String existingJurisdiction = existing.getTaxJurisdictionCode();
                                if (existingTaxCode == null || existingTaxCode.isBlank()) {
                                        financialHardening.applyTaxToDraftInvoice(existing.getId(),
                                                        resolvedJurisdiction,
                                                        resolvedTaxCode, LocalDate.now());
                                        entityManager.refresh(existing);
                                } else if (!resolvedTaxCode.equalsIgnoreCase(existingTaxCode)
                                                || existingJurisdiction == null
                                                || !resolvedJurisdiction.equalsIgnoreCase(existingJurisdiction)) {
                                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                        "This draft has a different tax rule. Review it in Financial hardening before issuing it; billing cannot silently replace an already-applied tax calculation.");
                                }
                                // Use the shared lifecycle transition as the single posting and
                                // history boundary. Tax is guaranteed to be applied before issue.
                                financialHardening.transitionInvoice(existing.getId(), "ISSUED",
                                                "Draft invoice issued by shipment billing");
                                entityManager.refresh(existing);
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

                // VAT is resolved and calculated by the backend for every issued
                // shipment invoice. Omitting tax fields in an API request cannot bypass VAT.
                financialHardening.applyTaxToDraftInvoice(invoice.getId(), resolvedJurisdiction,
                                resolvedTaxCode, LocalDate.now());
                entityManager.refresh(invoice);
                // One canonical lifecycle transition owns the ledger posting, status
                // update and immutable lifecycle-history row for every invoice path.
                financialHardening.transitionInvoice(invoice.getId(), "ISSUED",
                                "Invoice issued from shipment billing");
                entityManager.refresh(invoice);
                return invoice;
        }

        /**
         * Canonical issue path for all invoice entry points, including invoices
         * created manually or from accepted quotations. Tax is resolved on the
         * server for the invoice issue date and currency, applied to a draft, and
         * posted through the same lifecycle boundary as shipment invoices.
         *
         * The amount supplied here is the commercial amount before tax when the
         * configured tax rule is exclusive, or the gross amount when the rule is
         * inclusive. The rule's inclusive flag is authoritative; clients cannot
         * select a different treatment in the request.
         */
        @Transactional
        public CommercialInvoice createAndIssueCommercialInvoice(
                        String requestedInvoiceNo,
                        LocalDate issueDate,
                        String client,
                        UUID shipmentId,
                        String requestedCurrency,
                        BigDecimal amount,
                        LocalDate dueDate,
                        String owner,
                        String notes) {
                UUID tenantId = TenantContext.getTenantId();
                if (issueDate == null) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice issue date is required");
                }
                if (client == null || client.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice customer is required");
                }
                if (amount == null || amount.signum() <= 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Invoice amount must be greater than zero");
                }
                if (dueDate != null && dueDate.isBefore(issueDate)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Invoice due date cannot be before its issue date");
                }

                String currency = normalizeCurrency(requestedCurrency);
                if (!currency.matches("[A-Z]{3}")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Invoice currency must be a three-letter ISO currency code");
                }
                try {
                        InvoiceTaxCalculator.currencyScale(currency);
                } catch (IllegalArgumentException ex) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
                }

                String invoiceNo = requestedInvoiceNo == null || requestedInvoiceNo.isBlank()
                                ? documentSequences.nextInvoiceNumber()
                                : requestedInvoiceNo.trim();
                if (invoiceNo.length() > 80) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Invoice number cannot exceed 80 characters");
                }
                if (invoices.findByTenantIdAndInvoiceNo(tenantId, invoiceNo).isPresent()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Invoice number already exists. Use the existing invoice or a new invoice number.");
                }

                if (shipmentId != null) {
                        Shipment shipment = shipments.findLockedByIdAndTenantId(shipmentId, tenantId)
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                        "Shipment not found"));
                        if (invoices.findByTenantIdAndShipmentId(tenantId, shipmentId).isPresent()) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                                "This shipment already has a canonical invoice. Open that invoice instead of creating a duplicate.");
                        }
                        if (shipment.getCurrency() != null
                                        && !shipment.getCurrency().isBlank()
                                        && !shipment.getCurrency().equalsIgnoreCase(currency)) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "Invoice currency must match the shipment currency");
                        }
                        BigDecimal governedAmount = shipment.getAmountBilledToClient();
                        if (governedAmount == null || governedAmount.signum() <= 0) {
                                governedAmount = shipment.getClientRevenue();
                        }
                        if (governedAmount != null && governedAmount.signum() > 0
                                        && governedAmount.compareTo(amount) != 0) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                "Invoice amount must match the shipment's governed client revenue");
                        }
                }

                java.util.Map<String, Object> defaultVatRule = financialHardening.defaultInvoiceVatRule(issueDate,
                                currency);
                String jurisdictionCode = java.util.Objects.toString(defaultVatRule.get("jurisdiction_code"), "");
                String taxCode = java.util.Objects.toString(defaultVatRule.get("code"), "");
                if (jurisdictionCode.isBlank() || taxCode.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "No active default VAT rule is configured for this invoice date and currency");
                }

                CommercialInvoice invoice = new CommercialInvoice(
                                tenantId,
                                invoiceNo,
                                issueDate,
                                client.trim(),
                                shipmentId,
                                currency,
                                amount,
                                dueDate,
                                owner == null || owner.isBlank() ? null : owner.trim());
                invoice.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
                invoice.changeLifecycleStatus("DRAFT");
                invoice = invoices.saveAndFlush(invoice);
                invoices.insertLifecycleHistory(tenantId, invoice.getId(), null, "DRAFT",
                                "Draft invoice created through canonical billing", currentUser());

                financialHardening.applyTaxToDraftInvoice(invoice.getId(), jurisdictionCode, taxCode, issueDate);
                entityManager.refresh(invoice);
                financialHardening.transitionInvoice(invoice.getId(), "ISSUED",
                                "Invoice issued through canonical billing with configured VAT");
                entityManager.refresh(invoice);
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
                return recordPayment(invoiceId, amount, currency, idempotencyKey,
                                reference, incomeSourceId, "UNSPECIFIED");
        }

        /** Require an explicit payment channel for cashier-entered receipts. */
        @Transactional
        public CommercialInvoice recordPayment(
                        UUID invoiceId,
                        BigDecimal amount,
                        String currency,
                        String idempotencyKey,
                        String reference,
                        UUID incomeSourceId,
                        String paymentMethod) {

                String method = normalizePaymentMethod(paymentMethod);
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
                                                        incomeSourceId)
                                        || !method.equals(prior.getPaymentMethod())) {

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
                                        || !java.util.Objects.equals(lockedPrior.getIncomeSourceId(), incomeSourceId)
                                        || !method.equals(lockedPrior.getPaymentMethod())) {
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

                CommercialPayment payment = new CommercialPayment(
                                tenantId,
                                invoice.getId(),
                                amount,
                                invoiceCurrency,
                                key,
                                normalizeReference(reference),
                                incomeSourceId,
                                receiptNo);
                payment.setPaymentMethod(method);
                payment = payments.saveAndFlush(payment);

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
                String value = invoice.getLifecycleStatus();
                return value == null || value.isBlank() ? "ISSUED" : value;
        }

        private static UUID currentUser() {
                Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                                ? null
                                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
                return principal instanceof TenantPrincipal tp ? tp.userId() : null;
        }

        @Transactional(readOnly = true)
        public java.util.List<CommercialPayment> paymentHistory(UUID invoiceId) {
                UUID tenant = TenantContext.getTenantId();
                invoices.findByTenantIdAndId(tenant, invoiceId).orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
                return payments.findAllByTenantIdAndInvoiceIdOrderByCreatedAtAsc(tenant, invoiceId);
        }

        private static String normalizePaymentMethod(String value) {
                if (value == null)
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Payment method is required");
                String method = value.trim().toUpperCase(Locale.ROOT);
                if (!java.util.Set.of("CASH", "MOBILE_MONEY", "BANK_TRANSFER", "CARD", "OTHER", "UNSPECIFIED")
                                .contains(method))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Unsupported payment method");
                return method;
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
