package com.logiplatform.controller;

import com.logiplatform.service.AalExcelImportService;
import com.logiplatform.service.AalExcelPreflightService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/command-center/import")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AalExcelImportController {
    private final AalExcelImportService service;
    private final AalExcelPreflightService preflight;

    public AalExcelImportController(AalExcelImportService service, AalExcelPreflightService preflight) {
        this.service = service;
        this.preflight = preflight;
    }

    /** Preflight does not write any shipment, invoice or journal entries. */
    @PostMapping(value = "/excel/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AalExcelPreflightService.Preview preview(
            @RequestPart("files") List<MultipartFile> files) {
        return preflight.preview(files);
    }

    /**
     * Backward-compatible single-workbook import.
     */
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AalExcelImportService.ImportResult importExcel(
            @RequestPart("file") MultipartFile file) {
        return service.importWorkbook(file);
    }

    /**
     * Preferred migration endpoint. Upload the MOTHERSHIP and Command Center
     * workbooks together. The service identifies sheets from their headers,
     * not from filenames or worksheet names, and de-duplicates shared AWBs.
     */
    @PostMapping(value = "/excel/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AalExcelImportService.ImportResult importExcelBatch(
            @RequestPart("files") List<MultipartFile> files) {
        return service.importWorkbooks(files);
    }
}
