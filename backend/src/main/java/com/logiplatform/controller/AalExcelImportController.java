package com.logiplatform.controller;

import com.logiplatform.service.AalExcelImportService;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/command-center/import")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AalExcelImportController {
    private final AalExcelImportService service;

    public AalExcelImportController(AalExcelImportService s) {
        service = s;
    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AalExcelImportService.ImportResult importExcel(@RequestPart("file") MultipartFile file) {
        return service.importWorkbook(file);
    }
}
