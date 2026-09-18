package com.logiplatform.service;

import com.logiplatform.model.Driver;
import com.logiplatform.repository.DriverRepository;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import static com.logiplatform.dto.TmsDtos.*;



@Service
public class DriverService {

    private final DriverRepository driverRepository;

    public DriverService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional
    public DriverResponse create(CreateDriverRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (driverRepository.existsByTenantIdAndLicenseNumber(tenantId, request.licenseNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A driver with license '" + request.licenseNumber() + "' already exists");
        }
        Driver saved = driverRepository.save(
                new Driver(tenantId, request.fullName(), request.licenseNumber(), request.phone()));
        return DriverResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DriverResponse> list() {
        UUID tenantId = TenantContext.getTenantId();
        return driverRepository.findAllByTenantId(tenantId).stream().map(DriverResponse::from).toList();
    }

    Driver getOwned(UUID driverId) {
        UUID tenantId = TenantContext.getTenantId();
        return driverRepository.findByIdAndTenantId(driverId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));
    }

    Driver save(Driver driver) {
        return driverRepository.save(driver);
    }
}

