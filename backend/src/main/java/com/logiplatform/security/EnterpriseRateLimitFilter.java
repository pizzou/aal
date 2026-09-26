package com.logiplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

@Component
public class EnterpriseRateLimitFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis; private final int defaultLimit; private final int loginLimit; private final int bookingLimit; private final int webhookLimit; private final int uploadLimit; private final int searchLimit; private final int awbLimit; private final int adminLimit;
    public EnterpriseRateLimitFilter(StringRedisTemplate redis,
            @Value("${integration.rate-limit.default-per-minute:120}") int defaultLimit,
            @Value("${integration.rate-limit.login-per-minute:10}") int loginLimit,
            @Value("${integration.rate-limit.booking-per-minute:30}") int bookingLimit,
            @Value("${integration.rate-limit.webhook-per-minute:120}") int webhookLimit,
            @Value("${integration.rate-limit.upload-per-minute:30}") int uploadLimit,
            @Value("${integration.rate-limit.search-per-minute:60}") int searchLimit,
            @Value("${integration.rate-limit.awb-per-minute:30}") int awbLimit,
            @Value("${integration.rate-limit.admin-per-minute:60}") int adminLimit){this.redis=redis;this.defaultLimit=defaultLimit;this.loginLimit=loginLimit;this.bookingLimit=bookingLimit;this.webhookLimit=webhookLimit;this.uploadLimit=uploadLimit;this.searchLimit=searchLimit;this.awbLimit=awbLimit;this.adminLimit=adminLimit;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        int limit=limit(req); if(limit<=0){chain.doFilter(req,res);return;}
        String bucket=category(req); String identity=identity(req); String key="aal:rl:"+bucket+":"+identity+":"+(System.currentTimeMillis()/60000);
        try{Long count=redis.opsForValue().increment(key); if(count!=null&&count==1) redis.expire(key,Duration.ofSeconds(65)); if(count!=null&&count>limit){res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());res.setHeader("Retry-After","60");res.setContentType("application/json");res.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests\"}");return;}}catch(Exception ignored){/* Redis outage must not turn rate limiting into an application outage. */}
        chain.doFilter(req,res);
    }
    private int limit(HttpServletRequest r){String p=r.getRequestURI().toLowerCase(Locale.ROOT);if(p.equals("/api/auth/login")||p.equals("/api/auth/send-login-otp"))return loginLimit;if(p.contains("webhook"))return webhookLimit;if(p.contains("/air-cargo/bookings"))return bookingLimit;if(p.contains("/awb"))return awbLimit;if(p.contains("upload")||"POST".equalsIgnoreCase(r.getMethod())&&p.contains("documents"))return uploadLimit;if(p.contains("search"))return searchLimit;if(p.startsWith("/api/users")||p.startsWith("/api/settings")||p.startsWith("/api/platform"))return adminLimit;return defaultLimit;}
    private String category(HttpServletRequest r){return r.getRequestURI().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_");}
    private String identity(HttpServletRequest r){Authentication a=SecurityContextHolder.getContext().getAuthentication();if(a!=null&&a.isAuthenticated()&&a.getName()!=null)return a.getName();String ip=r.getHeader("X-Forwarded-For");if(ip!=null&&!ip.isBlank())return ip.split(",")[0].trim();return r.getRemoteAddr();}
}
