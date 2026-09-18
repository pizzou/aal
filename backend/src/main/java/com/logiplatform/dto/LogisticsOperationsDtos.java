package com.logiplatform.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class LogisticsOperationsDtos {
    private LogisticsOperationsDtos() {}
    public record RateRequest(String quoteNumber,UUID shipmentId,String serviceName,String mode,String originCode,String destinationCode,String currency,BigDecimal baseAmount,BigDecimal fuelSurcharge,BigDecimal securitySurcharge,BigDecimal handlingAmount,BigDecimal customsAmount,BigDecimal otherAmount,Instant validFrom,Instant validUntil,String terms) {}
    public record ExceptionRequest(UUID shipmentId,String severity,String exceptionType,String title,String description,String owner,Instant dueAt) {}
}
