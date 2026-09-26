package com.logiplatform.integration.control;

import org.springframework.http.HttpMethod;

/** Explicit retry classification for externally visible operations. */
public enum OperationRetryPolicy {
    SAFE_TO_RETRY,
    CONDITIONALLY_RETRYABLE,
    NEVER_RETRY;

    public static OperationRetryPolicy classify(String operation, String idempotencyKey) {
        String op = operation == null ? "" : operation.trim().toUpperCase();
        if (op.startsWith("GET") || op.contains("STATUS") || op.contains("SCHEDULE") || op.contains("CAPACITY") || op.contains("WEBHOOK")) {
            return SAFE_TO_RETRY;
        }
        if (op.contains("BOOK") || op.contains("AMEND") || op.contains("CANCEL") || op.contains("AWB") || op.contains("CUSTOMS")) {
            return idempotencyKey == null || idempotencyKey.isBlank()
                    ? NEVER_RETRY : CONDITIONALLY_RETRYABLE;
        }
        if (op.contains("FINANCIAL") || op.contains("PAYMENT") || op.contains("POST")) {
            return idempotencyKey == null || idempotencyKey.isBlank()
                    ? NEVER_RETRY : CONDITIONALLY_RETRYABLE;
        }
        return NEVER_RETRY;
    }

    public static OperationRetryPolicy classify(HttpMethod method, String idempotencyKey) {
        if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) {
            return SAFE_TO_RETRY;
        }
        return idempotencyKey == null || idempotencyKey.isBlank()
                ? NEVER_RETRY : CONDITIONALLY_RETRYABLE;
    }
}
