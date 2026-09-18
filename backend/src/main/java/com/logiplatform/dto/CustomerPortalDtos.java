package com.logiplatform.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class CustomerPortalDtos {
    private CustomerPortalDtos() {}
    public record Profile(UUID userId,String email,String clientId,String company,String contact,String phone,String country,String city) {}
    public record Shipment(UUID id,String referenceCode,String status,String mode,String origin,String destination,Instant eta,String carrier,String trackingToken) {}
    public record Quote(UUID id,String quoteId,LocalDate quoteDate,String client,String route,String serviceType,BigDecimal quotedAmount,String currency,LocalDate validUntil,String status) {}
    public record Invoice(UUID id,String invoiceNo,UUID shipmentId,BigDecimal amount,BigDecimal paid,BigDecimal outstanding,String currency,String status,LocalDate dueDate) {}
    public record Document(UUID id,UUID shipmentId,String type,String uri,Instant createdAt) {}
    public record Event(UUID id,String type,String location,String notes,Instant occurredAt) {}
}
