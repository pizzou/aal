package com.logiplatform.dto;
import java.time.Instant; import java.util.*;
public final class JourneyDtos { private JourneyDtos(){}
 public record Leg(UUID id,int sequenceNo,String mode,String origin,String destination,String carrier,String reference,Instant etd,Instant eta,Instant actualDeparture,Instant actualArrival,String status,List<Milestone> milestones,List<Cost> costs,List<Document> documents){}
 public record Milestone(UUID id,String type,String location,Instant plannedAt,Instant actualAt,String status,String notes){}
 public record Cost(UUID id,String description,java.math.BigDecimal amount,String currency,String supplier){}
 public record Document(UUID id,String type,String uri,boolean customerVisible,String status){}
 public record Journey(UUID shipmentId,List<Leg> legs,Instant unifiedEta,String currentStatus){}
}
