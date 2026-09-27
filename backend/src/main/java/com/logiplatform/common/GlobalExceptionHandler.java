package com.logiplatform.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        if (status >= 500) log.error("Request failed status={} code={} requestId={} correlationId={}",
                status, codeFor(status, ex.getReason()), requestId(), correlationId(), ex);
        return response(status, codeFor(status, ex.getReason()), safeMessage(ex.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return response(400, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DataIntegrityViolationException ex) {
        log.warn("Data integrity conflict requestId={} correlationId={}", requestId(), correlationId());
        return response(409, "DATA_CONFLICT", "The requested operation conflicts with existing data");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return response(400, "INVALID_PARAMETER", "Invalid request parameter");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return response(400, "INVALID_REQUEST", safeMessage(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal application state requestId={} correlationId={}", requestId(), correlationId(), ex);
        return response(500, "INTERNAL_ERROR", "Internal server error");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled application exception requestId={} correlationId={}", requestId(), correlationId(), ex);
        return response(500, "INTERNAL_ERROR", "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> response(int status, String code, String message) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(errorBody(status, code, message));
    }

    private Map<String, Object> errorBody(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        if (requestId() != null) body.put("requestId", requestId());
        if (correlationId() != null) body.put("correlationId", correlationId());
        body.put("details", List.of());
        return body;
    }

    private static String requestId() { return MDC.get("requestId"); }
    private static String correlationId() { return MDC.get("correlationId"); }

    private static String codeFor(int status, String reason) {
        if (reason == null) return "HTTP_" + status;
        String normalized = reason.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "Request could not be completed";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
