package com.logiplatform.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BrowserCsrfFilterTest {
    private final BrowserCsrfFilter filter = new BrowserCsrfFilter();

    @Test
    void browserCookieMutationWithoutCsrfTokenIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/shipments");
        request.setCookies(new jakarta.servlet.http.Cookie("NLS_SESSION", "session"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void browserCookieMutationRequiresMatchingCookieAndHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/shipments/1");
        request.setCookies(new jakarta.servlet.http.Cookie("NLS_SESSION", "session"),
                new jakarta.servlet.http.Cookie("NLS_CSRF", "random-token"));
        request.addHeader("X-CSRF-Token", "random-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void safeReadDoesNotRequireCsrfHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shipments");
        request.setCookies(new jakarta.servlet.http.Cookie("NLS_SESSION", "session"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }
}
