package com.logiplatform.controller;

import com.logiplatform.dto.ReportingDtos.DashboardResponse;
import com.logiplatform.dto.ReportingDtos.ManagementReport;
import com.logiplatform.service.ReportingService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(
            ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    /**
     * Canonical management report.
     *
     * Default:
     * last 30 calendar days ending today.
     *
     * Example:
     * GET /api/reports/management
     *
     * Example:
     * GET /api/reports/management?from=2026-09-01&to=2026-09-15
     */
    @GetMapping("/management")
    public ResponseEntity<ManagementReport> management(
            @RequestParam(required = false) String from,

            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(
                reportingService.managementReport(
                        parseDate(from),
                        parseDate(to)));
    }

    /**
     * Backward-compatible reporting endpoint.
     *
     * Existing frontend/integration consumers can continue using:
     *
     * GET /api/reports/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> dashboard() {
        return ResponseEntity.ok(
                reportingService.dashboard());
    }

    private static LocalDate parseDate(
            String value) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Date must use YYYY-MM-DD format");
        }
    }

}
