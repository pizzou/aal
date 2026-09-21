package com.logiplatform.controller;

import com.logiplatform.service.PublicTrackingService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import static com.logiplatform.dto.PublicTrackingDtos.*;

@RestController
@RequestMapping("/api/public/tracking")
public class PublicTrackingController {

    private final PublicTrackingService publicTrackingService;

    public PublicTrackingController(PublicTrackingService publicTrackingService) {
        this.publicTrackingService = publicTrackingService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<PublicShipmentView> track(@PathVariable UUID token) {
        return ResponseEntity.ok(publicTrackingService.findByToken(token));
    }

    @PostMapping("/{token}/feedback")
    public ResponseEntity<java.util.Map<String,Object>> feedback(
            @PathVariable UUID token,
            @Valid @RequestBody PublicFeedbackRequest request) {
        return ResponseEntity.ok(publicTrackingService.feedback(token, request));
    }
}
