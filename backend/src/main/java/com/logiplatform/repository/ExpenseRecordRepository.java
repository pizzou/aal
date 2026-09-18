
package com.logiplatform.repository;

import com.logiplatform.model.ExpenseRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRecordRepository
        extends JpaRepository<ExpenseRecord, UUID> {

    List<ExpenseRecord> findAllByTenantIdOrderByExpenseDateDesc(
            UUID tenantId);

    Optional<ExpenseRecord> findByTenantIdAndExpenseId(
            UUID tenantId,
            String expenseId);
}
