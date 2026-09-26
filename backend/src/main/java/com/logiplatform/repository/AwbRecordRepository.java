package com.logiplatform.repository;
import com.logiplatform.model.AwbRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AwbRecordRepository extends JpaRepository<AwbRecord,UUID>{
 List<AwbRecord> findAllByTenantIdOrderByCreatedAtDesc(UUID t);
 List<AwbRecord> findAllByTenantIdAndShipmentIdOrderByCreatedAtDesc(UUID t,UUID s);
 Optional<AwbRecord> findByTenantIdAndId(UUID t,UUID id);
 Optional<AwbRecord> findByTenantIdAndAwbNumber(UUID t,String awbNumber);
 Optional<AwbRecord> findByTenantIdAndCarrierReference(UUID t,String carrierReference);
}
