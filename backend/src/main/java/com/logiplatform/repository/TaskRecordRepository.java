package com.logiplatform.repository;

import com.logiplatform.model.TaskRecord;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRecordRepository
        extends JpaRepository<TaskRecord, UUID> {

    List<TaskRecord> findAllByTenantIdOrderByDueDateAsc(
            UUID tenantId);

    Optional<TaskRecord> findByTenantIdAndTaskId(
            UUID tenantId,
            String taskId);

}
