package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ModeOperationsDtos {
    private ModeOperationsDtos() {}
    public record VoyageRequest(@NotBlank String vesselName,String imoNumber,@NotBlank String voyageNumber,String carrierName,String serviceName,String originPort,String destinationPort,Instant etd,Instant eta,BigDecimal capacityTeu) {}
    public record ContainerRequest(@NotBlank String containerNumber,@NotBlank String containerType,String sealNumber,BigDecimal grossWeightKg,BigDecimal vgmWeightKg) {}
    public record BookingRequest(@NotNull UUID shipmentId,UUID voyageId,@NotBlank String bookingNumber,@NotBlank String bookingType,@Min(1) int requestedContainers) {}
    public record RoadRequest(String cmrNumber,String vehicleRegistration,String trailerRegistration,String driverName,String driverPhone) {}
    public record RailRequest(String railConsignmentNumber,String trainNumber,String wagonNumbers,String originTerminal,String destinationTerminal) {}
}
