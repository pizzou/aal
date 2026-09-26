package com.logiplatform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class ProviderResponseValidator {
    public void booking(JsonNode node) {
        if(node==null||!node.isObject()) throw new IllegalStateException("Provider booking response must be a JSON object");
        String status=node.path("status").asText("");
        if(status.isBlank()) throw new IllegalStateException("Provider booking response is missing status");
        if(node.has("confirmedWeightKg")&&!node.get("confirmedWeightKg").isNumber()) throw new IllegalStateException("Provider booking response contains invalid confirmedWeightKg");
        if(node.has("providerReference")&&!node.get("providerReference").isTextual()) throw new IllegalStateException("Provider booking response contains invalid providerReference");
    }
    public void capacity(JsonNode node) {
        if(node==null||!node.isObject()) throw new IllegalStateException("Provider capacity response must be a JSON object");
        BigDecimal available=decimal(node,"availableCapacityKg"), total=decimal(node,"totalCapacityKg");
        if(available==null||total==null||available.signum()<0||total.signum()<0||available.compareTo(total)>0) throw new IllegalStateException("Provider returned invalid capacity");
    }
    private static BigDecimal decimal(JsonNode n,String field){try{return n.hasNonNull(field)?n.get(field).decimalValue():null;}catch(Exception ex){return null;}}
}
