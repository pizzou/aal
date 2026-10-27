package com.logiplatform.repository;

import com.logiplatform.model.CommercialPayment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CommercialPaymentRepository
                extends JpaRepository<CommercialPayment, UUID> {

        /**
         * Canonical idempotency lookup.
         */
        Optional<CommercialPayment> findByTenantIdAndIdempotencyKey(
                        UUID tenantId,
                        String idempotencyKey);

        /**
         * Tenant-scoped payment lookup used when generating a receipt.
         */
        Optional<CommercialPayment> findByTenantIdAndId(
                        UUID tenantId,
                        UUID id);

        java.util.List<CommercialPayment> findAllByTenantIdAndInvoiceIdOrderByCreatedAtAsc(
                        UUID tenantId, UUID invoiceId);

        /**
         * Returns the most recently recorded payment for an invoice.
         */
        Optional<CommercialPayment> findTopByTenantIdAndInvoiceIdOrderByCreatedAtDesc(
                        UUID tenantId,
                        UUID invoiceId);
}