package com.logiplatform.controller;

import com.logiplatform.service.MonthlySummaryService;

import org.springframework.web.bind.annotation.*;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/command-center")
public class MonthlySummaryController {
    private final MonthlySummaryService s;

    public MonthlySummaryController(MonthlySummaryService s) {
        this.s = s;
    }

    @GetMapping("/monthly-summary")
    public MonthlySummaryService.Summary summary(@RequestParam(required = false) String month) {
        return s.summary(month == null ? YearMonth.now() : YearMonth.parse(month));
    }
}
