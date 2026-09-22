package com.logiplatform.service;

import com.logiplatform.repository.RateCardRepository;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.math.*;
import java.util.*;

@Service
public class DynamicPricingService {
    private final RestTemplate rest; private final RateCardRepository rates;
    private final String aiBaseUrl,aiKey,aiModel; private final boolean aiEnabled;
    private final ObjectMapper mapper=new ObjectMapper();

    public DynamicPricingService(RestTemplate r,RateCardRepository rates,
        @Value("${pricing.ai.base-url:https://api.openai.com/v1}") String base,
        @Value("${pricing.ai.api-key:}") String key,
        @Value("${pricing.ai.model:gpt-5-mini}") String model,
        @Value("${pricing.ai.enabled:false}") boolean enabled){
        rest=r;this.rates=rates;aiBaseUrl=base;aiKey=key;aiModel=model;aiEnabled=enabled;
    }

    public PricingRecommendation recommend(String transportMode,BigDecimal weightKg,BigDecimal supplierCost,
                                           BigDecimal capacityUtilizationPercent,Integer daysToDeparture){
        if(weightKg==null||weightKg.signum()<=0)throw new IllegalArgumentException("weightKg must be positive");
        var card=rates.findFirstEffective(TenantContext.getTenantId(),transportMode.toUpperCase(),java.time.LocalDate.now())
                .orElseThrow(()->new IllegalArgumentException("No rate card configured for "+transportMode));
        BigDecimal base=(supplierCost==null?card.getMinCharge():supplierCost).max(card.getMinCharge());
        BigDecimal factor=BigDecimal.ONE;
        if(capacityUtilizationPercent!=null&&capacityUtilizationPercent.compareTo(BigDecimal.valueOf(85))>=0)factor=factor.add(new BigDecimal("0.12"));
        if(daysToDeparture!=null&&daysToDeparture<=2)factor=factor.add(new BigDecimal("0.08"));
        BigDecimal heuristic=base.multiply(factor).max(card.getMinCharge()).setScale(2,RoundingMode.HALF_UP);
        if(!aiEnabled||aiKey.isBlank())return new PricingRecommendation(heuristic,heuristic.subtract(base),"HEURISTIC_CAPACITY_URGENCY","AI_DISABLED");
        try{
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("model",aiModel); body.put("temperature",0.1);
            body.put("messages",List.of(
                Map.of("role","system","content","Return JSON only: {\"multiplier\": number}. Multiplier must be between 0.8 and 1.5. Never output anything else."),
                Map.of("role","user","content","Freight spot pricing inputs: mode="+transportMode+", weightKg="+weightKg+
                    ", supplierCost="+supplierCost+", capacityUtilizationPercent="+capacityUtilizationPercent+
                    ", daysToDeparture="+daysToDeparture)));
            HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.setBearerAuth(aiKey);
            ResponseEntity<String> response=rest.exchange(aiBaseUrl+"/chat/completions",HttpMethod.POST,new HttpEntity<>(body,h),String.class);
            JsonNode root=mapper.readTree(response.getBody());
            String content=root.path("choices").path(0).path("message").path("content").asText();
            JsonNode json=mapper.readTree(content);
            BigDecimal mult=new BigDecimal(json.path("multiplier").asText()).max(new BigDecimal("0.8")).min(new BigDecimal("1.5"));
            BigDecimal price=base.multiply(mult).max(card.getMinCharge()).setScale(2,RoundingMode.HALF_UP);
            return new PricingRecommendation(price,price.subtract(base),"AI_DYNAMIC_SPOT",aiModel);
        }catch(Exception ignored){
            return new PricingRecommendation(heuristic,heuristic.subtract(base),"HEURISTIC_FALLBACK","AI_UNAVAILABLE");
        }
    }
    public record PricingRecommendation(BigDecimal recommendedPrice,BigDecimal estimatedProfit,String pricingMode,String source){}
}
