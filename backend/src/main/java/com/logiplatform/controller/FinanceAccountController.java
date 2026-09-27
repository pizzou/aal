package com.logiplatform.controller;

import com.logiplatform.model.FinanceAccount;
import com.logiplatform.repository.FinanceAccountRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/finance/accounts")
public class FinanceAccountController {
    private final FinanceAccountRepository repository;
    public FinanceAccountController(FinanceAccountRepository repository){this.repository=repository;}
    @GetMapping public List<FinanceAccount> list(){return repository.findAllByTenantIdAndActiveTrueOrderByAccountCode(TenantContext.getTenantId());}
}
