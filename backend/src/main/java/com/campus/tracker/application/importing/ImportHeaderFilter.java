package com.campus.tracker.application.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/** Runs before CsrfFilter; absent headers must never trigger its multipart parameter fallback. */
public final class ImportHeaderFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    public ImportHeaderFilter(ObjectMapper json) { this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ImportUploadPolicy.isImport(request)) {
            response.setHeader("Cache-Control", "no-store");
            if (CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) && (request.getHeader("X-CSRF-TOKEN") == null || request.getHeader("X-CSRF-TOKEN").isBlank())) {
                response.setHeader("Connection", "close");
                response.setStatus(403); response.setContentType("application/json");
                json.writeValue(response.getOutputStream(), Map.of("code", "IMPORT_CSRF_REQUIRED", "message", "A CSRF header is required."));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
