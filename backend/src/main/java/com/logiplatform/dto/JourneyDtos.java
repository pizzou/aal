package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JourneyDtos {
    private JourneyDtos() {}

    public record Leg(
            UUID id,
            int sequenceNo,
            String mode,
            String origin,
            String destination,
            String carrier,
            String reference,
            Instant etd,
            Instant eta,
            Instant actualDeparture,
            Instant actualArrival,
            String status,
            List<Milestone> milestones,
            List<Cost> costs,
            List<Document> documents) {}

    public record Milestone(
            UUID id,
            String type,
            String location,
            Instant plannedAt,
            Instant actualAt,
            String status,
            String notes) {}

    public record Cost(
            UUID id,
            String description,
            BigDecimal amount,
            String currency,
            String supplier) {}

    public record Document(
            UUID id,
            String type,
            String uri,
            boolean customerVisible,
            String status) {}

    public record Journey(
            UUID journeyId,
            UUID shipmentId,
            String journeyReference,
            String serviceType,
            Instant plannedStart,
            Instant plannedEnd,
            Instant actualStart,
            Instant actualEnd,
            Instant unifiedEta,
            String status,
            boolean customerVisible,
            List<Leg> legs,
            String currentShipmentStatus) {}
}
