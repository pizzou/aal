package com.logiplatform.controller;

import com.logiplatform.service.AdvancedDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static com.logiplatform.dto.AdvancedDashboardDtos.Response;

/**
 * Extended, read-only command-centre analytics.
 *
 * The canonical daily-operations endpoint is owned by CommandCenterController.
 * Keeping that endpoint in one controller prevents Spring MVC ambiguous-mapping
 * failures while preserving the existing /advanced dashboard contract.
 */
@RestController
@RequestMapping("/api/command-center")
public class AdvancedDashboardController {

    private final AdvancedDashboardService advanced;

    public AdvancedDashboardController(AdvancedDashboardService advanced) {
        this.advanced = advanced;
    }

    @GetMapping("/advanced")
    public Response advanced(@RequestParam(required = false) String asOf) {
        return advanced.dashboard(parseDate(asOf));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }

        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("Date must use YYYY-MM-DD format");
        }
    }
}
