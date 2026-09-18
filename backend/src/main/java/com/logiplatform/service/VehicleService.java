package com.logiplatform.service;

import com.logiplatform.model.Vehicle;
import com.logiplatform.repository.VehicleRepository;
import com.logiplatform.model.VehicleType;


import com.logiplatform.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import static com.logiplatform.dto.TmsDtos.*;



@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    public VehicleService(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    @Transactional
    public VehicleResponse create(CreateVehicleRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        if (vehicleRepository.existsByTenantIdAndRegistrationNumber(tenantId, request.registrationNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A vehicle with registration '" + request.registrationNumber() + "' already exists");
        }

        VehicleType type;
        try {
            type = VehicleType.valueOf(request.vehicleType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid vehicleType: " + request.vehicleType());
        }

        Vehicle saved = vehicleRepository.save(
                new Vehicle(tenantId, request.registrationNumber(), type, request.capacityKg()));
        return VehicleResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> list() {
        UUID tenantId = TenantContext.getTenantId();
        return vehicleRepository.findAllByTenantId(tenantId).stream().map(VehicleResponse::from).toList();
    }

    /** Package-visible: used by TripService to validate + mutate vehicle status within its own transaction. */
    Vehicle getOwned(UUID vehicleId) {
        UUID tenantId = TenantContext.getTenantId();
        return vehicleRepository.findByIdAndTenantId(vehicleId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found"));
    }

    Vehicle save(Vehicle vehicle) {
        return vehicleRepository.save(vehicle);
    }
}

