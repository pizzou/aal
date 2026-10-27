package com.logiplatform.controller;

import com.logiplatform.service.CustomsAuthorityCallbackService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Dedicated, signature-verified provider webhook (never a staff release button). */
@RestController
@RequestMapping("/api/provider/customs")
public class CustomsAuthorityCallbackController {
    private final CustomsAuthorityCallbackService callback;

    public CustomsAuthorityCallbackController(CustomsAuthorityCallbackService callback) {
        this.callback = callback;
    }

    @PostMapping(value = "/acknowledgments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> acknowledge(
            @RequestHeader(value = "X-AAL-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-AAL-Event-ID", required = false) String eventId,
            @RequestHeader(value = "X-AAL-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        callback.verify(rawBody, timestamp, eventId, signature);
        return callback.applyVerified(rawBody, eventId);
    }
}
