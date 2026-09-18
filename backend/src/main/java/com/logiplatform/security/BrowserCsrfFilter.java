package com.logiplatform.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class BrowserCsrfFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATING=Set.of("POST","PUT","PATCH","DELETE");
    private static final String SESSION="NLS_SESSION", CSRF_COOKIE="NLS_CSRF", CSRF_HEADER="X-CSRF-Token";

    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws ServletException,IOException{
        if(MUTATING.contains(req.getMethod()) && hasCookie(req,SESSION) && !csrfValid(req)){
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"CSRF validation failed\"}");return;
        }
        chain.doFilter(req,res);
    }

    private boolean csrfValid(HttpServletRequest req){
        String cookie=null;
        if(req.getCookies()!=null) for(Cookie c:req.getCookies()) if(CSRF_COOKIE.equals(c.getName())) cookie=c.getValue();
        String header=req.getHeader(CSRF_HEADER);
        return cookie!=null&&header!=null&&MessageDigest.isEqual(cookie.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private boolean hasCookie(HttpServletRequest req,String name){
        if(req.getCookies()==null)return false;
        for(Cookie c:req.getCookies())if(name.equals(c.getName()))return true;
        return false;
    }
}
