package com.logiplatform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Without this, tracing "what happened during this one request" through logs means
 * grepping by rough timestamp and hoping nothing else was happening concurrently —
 * which, at the traffic this platform is meant for, will usually be false. Every log
 * line emitted during a request now carries the same requestId via SLF4J's MDC, and
 * the response echoes it back in X-Request-Id so a client-reported error can be
 * matched directly to server-side log lines instead of guessed at.
 *
 * Runs first (HIGHEST_PRECEDENCE) so the ID is available to every other filter's logs,
 * including auth failures and rate-limit rejections.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        } else {
            try {
                requestId = UUID.fromString(requestId.trim()).toString();
            } catch (IllegalArgumentException ex) {
                requestId = UUID.randomUUID().toString();
            }
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // critical: threads are pooled and reused — leaving
                                  // this set would leak one request's ID into the next
                                  // request that happens to reuse the same thread.
        }
    }
}
