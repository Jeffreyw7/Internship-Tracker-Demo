package com.campus.tracker.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlate requests without retaining request bodies, credentials or account data. */
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-ID");
        String id = supplied != null && supplied.matches("[a-zA-Z0-9_-]{1,64}")
                ? supplied : UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", id);
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        try { chain.doFilter(request, response); }
        finally {
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }
}
