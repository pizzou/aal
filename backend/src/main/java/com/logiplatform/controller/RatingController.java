package com.logiplatform.controller;

import com.logiplatform.service.RateEngineService;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import static com.logiplatform.dto.RatingDtos.*;

@RestController
@RequestMapping("/api/rating")
public class RatingController {

    private final RateEngineService rateEngineService;

    public RatingController(RateEngineService rateEngineService) {
        this.rateEngineService = rateEngineService;
    }

    @PutMapping("/rate-cards")
    public ResponseEntity<RateCardResponse> setRateCard(@Valid @RequestBody SetRateCardRequest request) {
        return ResponseEntity.ok(rateEngineService.setRateCard(request));
    }

    @GetMapping("/rate-cards")
    public ResponseEntity<List<RateCardResponse>> listRateCards() {
        return ResponseEntity.ok(rateEngineService.listRateCards());
    }

    @PutMapping("/accessorials")
    public ResponseEntity<AccessorialResponse> setAccessorial(@Valid @RequestBody SetAccessorialRequest request) {
        return ResponseEntity.ok(rateEngineService.setAccessorial(request));
    }

    @GetMapping("/accessorials")
    public ResponseEntity<List<AccessorialResponse>> listAccessorials() {
        return ResponseEntity.ok(rateEngineService.listAccessorials());
    }

    @PostMapping("/quote")
    public ResponseEntity<QuoteResponse> quote(@Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(rateEngineService.quote(request));
    }
}
