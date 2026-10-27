package com.logiplatform.controller;

import com.logiplatform.service.ClientCutoverReconciliationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Finance-controlled read-only reconciliation; does not certify historic Excel. */
@RestController
@RequestMapping("/api/finance/client-cutover")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
public class ClientCutoverReconciliationController {
    private final ClientCutoverReconciliationService service;
    public ClientCutoverReconciliationController(ClientCutoverReconciliationService service) {
        this.service = service;
    }
    @GetMapping("/payments")
    public List<ClientCutoverReconciliationService.CashMovement> payments(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return service.paymentRegister(from, to);
    }

    @GetMapping("/reconciliation")
    public ClientCutoverReconciliationService.Snapshot current() { return service.current(); }
}
