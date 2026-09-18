package com.logiplatform.repository;

import com.logiplatform.model.IoTDevice;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface IoTDeviceRepository extends JpaRepository<IoTDevice,UUID>{Optional<IoTDevice> findByTenantIdAndDeviceCode(UUID t,String code);}
