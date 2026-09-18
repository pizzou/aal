package com.logiplatform.service;

import com.logiplatform.dto.ModeOperationsDtos;
import com.logiplatform.model.*;
import com.logiplatform.repository.*;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ModeOperationsService {
    private final OceanVoyageRepository voyages;
    private final OceanContainerRepository containers;
    private final OceanBookingRepository bookings;
    private final RoadConsignmentRepository road;
    private final RailConsignmentRepository rail;
    private final ShipmentService shipments;

    public ModeOperationsService(OceanVoyageRepository voyages, OceanContainerRepository containers,
                                 OceanBookingRepository bookings, RoadConsignmentRepository road,
                                 RailConsignmentRepository rail, ShipmentService shipments) {
        this.voyages=voyages; this.containers=containers; this.bookings=bookings; this.road=road; this.rail=rail; this.shipments=shipments;
    }
    @Transactional(readOnly=true) public List<OceanVoyage> voyages(){return voyages.findAllByTenantIdOrderByEtdAsc(TenantContext.getTenantId());}
    @Transactional public OceanVoyage createVoyage(ModeOperationsDtos.VoyageRequest r){return voyages.save(new OceanVoyage(TenantContext.getTenantId(),r.vesselName(),r.imoNumber(),r.voyageNumber(),r.carrierName(),r.serviceName(),r.originPort(),r.destinationPort(),r.etd(),r.eta(),r.capacityTeu()));}
    @Transactional(readOnly=true) public List<OceanContainer> containers(UUID shipmentId){shipments.get(shipmentId); return containers.findAllByTenantIdAndShipmentId(TenantContext.getTenantId(),shipmentId);}
    @Transactional public OceanContainer createContainer(UUID shipmentId,ModeOperationsDtos.ContainerRequest r){shipments.get(shipmentId); return containers.save(new OceanContainer(TenantContext.getTenantId(),shipmentId,r.containerNumber(),r.containerType(),r.sealNumber(),r.grossWeightKg(),r.vgmWeightKg()));}
    @Transactional(readOnly=true) public List<OceanBooking> bookings(){return bookings.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId());}
    @Transactional public OceanBooking createBooking(ModeOperationsDtos.BookingRequest r){shipments.get(r.shipmentId()); return bookings.save(new OceanBooking(TenantContext.getTenantId(),r.shipmentId(),r.voyageId(),r.bookingNumber(),r.bookingType(),r.requestedContainers()));}
    @Transactional(readOnly=true) public List<RoadConsignment> roads(){return road.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId());}
    @Transactional public RoadConsignment createRoad(UUID shipmentId,ModeOperationsDtos.RoadRequest r){shipments.get(shipmentId); return road.save(new RoadConsignment(TenantContext.getTenantId(),shipmentId,r.cmrNumber(),r.vehicleRegistration(),r.trailerRegistration(),r.driverName(),r.driverPhone()));}
    @Transactional(readOnly=true) public List<RailConsignment> rails(){return rail.findAllByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId());}
    @Transactional public RailConsignment createRail(UUID shipmentId,ModeOperationsDtos.RailRequest r){shipments.get(shipmentId); return rail.save(new RailConsignment(TenantContext.getTenantId(),shipmentId,r.railConsignmentNumber(),r.trainNumber(),r.wagonNumbers(),r.originTerminal(),r.destinationTerminal()));}
}
