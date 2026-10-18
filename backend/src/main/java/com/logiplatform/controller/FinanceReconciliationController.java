package com.logiplatform.controller;

import com.logiplatform.dto.FinanceReconciliationDtos.Response;
import com.logiplatform.service.FinanceReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/finance/reconciliation")
public class FinanceReconciliationController {
    private final FinanceReconciliationService service;

    public FinanceReconciliationController(FinanceReconciliationService service) {
        this.service = service;
    }

    @GetMapping
    public Response reconcile(
            @RequestParam(required = false) String asOf,
            @RequestParam(required = false) String currency) {
        /*
         * Do not resolve an omitted date with LocalDate.now() here. The web server
         * commonly runs in UTC while finance reports use Africa/Kigali. Around
         * midnight, those two dates can differ and make an ordinary current-day
         * reconciliation fail as if it were a historical report.
         *
         * FinanceReconciliationService owns the business timezone and defaults a
         * null asOf to today's date in Africa/Kigali. Only parse a date if the
         * caller explicitly supplied one; its historical-date guard remains in
         * force until dated financial snapshots are implemented.
         */
        LocalDate requestedDate = (asOf == null || asOf.isBlank())
                ? null
                : LocalDate.parse(asOf.trim());
        return service.reconcile(requestedDate, currency);
    }
}
