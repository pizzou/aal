package com.logiplatform.controller;

import com.logiplatform.service.ReportingExportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
public class ReportingExportController {
    private final ReportingExportService exports;

    public ReportingExportController(ReportingExportService exports) {
        this.exports = exports;
    }

    @GetMapping(value="/management/export.xlsx",
            produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> xlsx(@RequestParam(required=false) String from,
                                       @RequestParam(required=false) String to) {
        byte[] body=exports.xlsx(date(from),date(to));
        return file(body,"aal-management-report.xlsx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @GetMapping(value="/management/export.csv", produces="text/csv")
    public ResponseEntity<byte[]> csv(@RequestParam(required=false) String from,
                                      @RequestParam(required=false) String to) {
        byte[] body=exports.csv(date(from),date(to));
        return file(body,"aal-management-report.csv",MediaType.parseMediaType("text/csv"));
    }

    @GetMapping(value="/management/export.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@RequestParam(required=false) String from,
                                      @RequestParam(required=false) String to) {
        byte[] body=exports.pdf(date(from),date(to));
        return file(body,"aal-management-report.pdf",MediaType.APPLICATION_PDF);
    }

    private static LocalDate date(String value) {
        if(value==null||value.isBlank()) return null;
        try{return LocalDate.parse(value);}
        catch(Exception e){throw new IllegalArgumentException("Date must use YYYY-MM-DD format");}
    }

    private static ResponseEntity<byte[]> file(byte[] body,String name,MediaType type){
        return ResponseEntity.ok().contentType(type).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);
    }
}
