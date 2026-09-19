package com.logiplatform.controller;

import com.logiplatform.dto.PublicQuoteDtos.QuoteResponseAction;
import com.logiplatform.dto.PublicQuoteDtos.QuoteView;
import com.logiplatform.service.PublicQuoteShareService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/quotes")
public class PublicQuoteController {
    private final PublicQuoteShareService service;
    public PublicQuoteController(PublicQuoteShareService service) { this.service = service; }

    @GetMapping("/{token}")
    public QuoteView view(@PathVariable String token) { return service.view(token); }

    @PostMapping("/{token}/response")
    public QuoteResponseAction response(@PathVariable String token, @RequestParam String action) {
        return service.respond(token, action);
    }
}
