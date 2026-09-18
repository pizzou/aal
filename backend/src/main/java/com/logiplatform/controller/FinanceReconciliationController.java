package com.logiplatform.controller;

import com.logiplatform.dto.FinanceReconciliationDtos.Response;
import com.logiplatform.service.FinanceReconciliationService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/finance/reconciliation")
public class FinanceReconciliationController {
    private final FinanceReconciliationService service;
    public FinanceReconciliationController(FinanceReconciliationService service){this.service=service;}
    @GetMapping
    public Response reconcile(@RequestParam(required=false) String asOf,@RequestParam(required=false) String currency){
        LocalDate date=asOf==null||asOf.isBlank()?LocalDate.now():LocalDate.parse(asOf);
        return service.reconcile(date,currency);
    }
}
