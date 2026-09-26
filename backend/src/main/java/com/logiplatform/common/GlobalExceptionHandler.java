package com.logiplatform.common;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,Object>> handleResponseStatus(ResponseStatusException ex){int status=ex.getStatusCode().value();return ResponseEntity.status(status).body(errorBody(status,codeFor(status,ex.getReason()),safeMessage(ex.getReason())));}
    @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<Map<String,Object>> handleValidation(MethodArgumentNotValidException ex){String message=ex.getBindingResult().getFieldErrors().stream().map(e->e.getField()+": "+e.getDefaultMessage()).reduce((a,b)->a+"; "+b).orElse("Validation failed");return ResponseEntity.badRequest().body(errorBody(400,"VALIDATION_ERROR",message));}
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<Map<String,Object>> handleConflict(DataIntegrityViolationException ex){return ResponseEntity.status(409).body(errorBody(409,"DATA_CONFLICT","The requested operation conflicts with existing data"));}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class) public ResponseEntity<Map<String,Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex){return ResponseEntity.badRequest().body(errorBody(400,"INVALID_PARAMETER","Invalid request parameter"));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,Object>> handleIllegalArgument(IllegalArgumentException ex){return ResponseEntity.badRequest().body(errorBody(400,"INVALID_REQUEST",safeMessage(ex.getMessage())));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,Object>> handleIllegalState(IllegalStateException ex){return ResponseEntity.status(500).body(errorBody(500,"INTERNAL_ERROR","Internal server error"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> handleGeneric(Exception ex){return ResponseEntity.status(500).body(errorBody(500,"INTERNAL_ERROR","Internal server error"));}
    private Map<String,Object> errorBody(int status,String code,String message){Map<String,Object> body=new LinkedHashMap<>();body.put("timestamp",Instant.now().toString());body.put("status",status);body.put("code",code);body.put("message",message);String requestId=MDC.get("requestId"),correlationId=MDC.get("correlationId");if(requestId!=null)body.put("requestId",requestId);if(correlationId!=null)body.put("correlationId",correlationId);body.put("details",List.of());return body;}
    private static String codeFor(int status,String reason){if(reason==null)return "HTTP_"+status;String normalized=reason.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+","_");return normalized.length()>80?normalized.substring(0,80):normalized;}
    private static String safeMessage(String message){if(message==null||message.isBlank())return "Request could not be completed";return message.length()>500?message.substring(0,500):message;}
}
