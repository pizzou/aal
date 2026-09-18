package com.logiplatform.dto;

import com.logiplatform.model.TransportLeg;


import jakarta.validation.constraints.*; import java.time.Instant; import java.util.UUID;
public final class TransportLegDtos {private TransportLegDtos(){} public record CreateRequest(@NotNull UUID shipmentId,@NotNull @Positive Integer sequenceNo,@NotBlank String mode,@NotBlank String origin,@NotBlank String destination,String carrierName,Instant plannedDeparture,Instant plannedArrival){} public record Response(UUID id,UUID shipmentId,Integer sequenceNo,String mode,String origin,String destination,String carrierName,String carrierReference,String status,Instant plannedDeparture,Instant plannedArrival){public static Response from(TransportLeg x){return new Response(x.getId(),x.getShipmentId(),x.getSequenceNo(),x.getMode(),x.getOrigin(),x.getDestination(),x.getCarrierName(),x.getCarrierReference(),x.getStatus(),x.getPlannedDeparture(),x.getPlannedArrival());}}}
