package com.logiplatform.controller;

import com.logiplatform.service.DynamicPricingService;

import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/rating/dynamic")
public class DynamicPricingController {
    private final DynamicPricingService service;

    public DynamicPricingController(DynamicPricingService s) {
        service = s;
    }

    public record Request(@NotBlank String transportMode, @NotNull @Positive BigDecimal weightKg,
            BigDecimal supplierCost, BigDecimal capacityUtilizationPercent, Integer daysToDeparture) {
    }

    @PostMapping("/recommend")
    public DynamicPricingService.PricingRecommendation recommend(@jakarta.validation.Valid @RequestBody Request r) {
        return service.recommend(r.transportMode(), r.weightKg(), r.supplierCost(), r.capacityUtilizationPercent(),
                r.daysToDeparture());
    }
}
