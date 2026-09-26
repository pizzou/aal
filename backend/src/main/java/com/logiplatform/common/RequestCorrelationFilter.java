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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID="X-Request-Id"; private static final String CORRELATION_ID="X-Correlation-Id";
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String requestId=normalize(request.getHeader(REQUEST_ID)); String correlationId=normalize(request.getHeader(CORRELATION_ID)); if(requestId==null)requestId=UUID.randomUUID().toString(); if(correlationId==null)correlationId=requestId;
        MDC.put("requestId",requestId); MDC.put("correlationId",correlationId); response.setHeader(REQUEST_ID,requestId); response.setHeader(CORRELATION_ID,correlationId);
        try{chain.doFilter(request,response);}finally{MDC.remove("requestId");MDC.remove("correlationId");}
    }
    private static String normalize(String v){if(v==null||v.isBlank())return null;try{return UUID.fromString(v.trim()).toString();}catch(Exception e){return null;}}
}
