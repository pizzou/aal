package com.logiplatform.controller;

import com.logiplatform.dto.CommandCenterDtos.DailyOperationsResponse;
import com.logiplatform.dto.ReportingDtos.ManagementReport;
import com.logiplatform.service.DailyOperationsService;
import com.logiplatform.service.ReportingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * AAL command-center compatibility boundary. Commercial CRUD is intentionally
 * owned by /api/commercial; this controller exposes only the operating view.
 */
@RestController
@RequestMapping("/api/command-center")
public class CommandCenterController {
    private final ReportingService reportingService;
    private final DailyOperationsService dailyOperationsService;

    public CommandCenterController(ReportingService reportingService, DailyOperationsService dailyOperationsService) {
        this.reportingService = reportingService;
        this.dailyOperationsService = dailyOperationsService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ManagementReport> dashboard(@RequestParam(required = false) String asOf) {
        LocalDate date = parseDate(asOf);
        return ResponseEntity.ok(reportingService.managementReport(date, date));
    }

    @GetMapping("/daily-operations")
    public DailyOperationsResponse dailyOperations(@RequestParam(required = false) String date) {
        return dailyOperationsService.operations(parseDate(date));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank())
            return LocalDate.now();
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("Date must use YYYY-MM-DD format");
        }
    }
}
