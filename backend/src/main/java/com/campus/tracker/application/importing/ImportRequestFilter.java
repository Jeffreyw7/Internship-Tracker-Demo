package com.campus.tracker.application.importing;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authorization precedes durable rates, transport checks and process permits. */
public final class ImportRequestFilter extends OncePerRequestFilter {
    private final ImportAdmissionService admission;
    private final ImportWorkLimiter work;
    private final ObjectMapper json;
    private final ImportInputLimits limits;
    public ImportRequestFilter(ImportAdmissionService admission, ImportWorkLimiter work, ObjectMapper json, ImportInputLimits limits) {
        this.admission = admission; this.work = work; this.json = json; this.limits = limits;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ImportUploadPolicy.isImport(request)) { chain.doFilter(request, response); return; }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CampusPrincipal principal)) {
            chain.doFilter(request, response); return;
        }
        boolean acquired = false;
        try {
            if (ImportUploadPolicy.isPreview(request)) ImportUploadPolicy.checkTransport(request, limits);
            String operation = "GET".equals(request.getMethod()) ? "status" : ImportUploadPolicy.isPreview(request) ? "preview" : "commit";
            try { admission.checkRate(principal.id(), operation); }
            catch (DataAccessException e) { throw ImportUploadPolicy.error(HttpStatus.TOO_MANY_REQUESTS,
                    "IMPORT_RATE_UNAVAILABLE", "Import admission is temporarily unavailable. Try again shortly."); }
            if (ImportUploadPolicy.isPreview(request)) {
                ImportUploadPolicy.begin(request, limits);
                work.acquire(principal.id()); acquired = true;
            }
            chain.doFilter(request, response);
        } catch (ApiException e) {
            // Tomcat schedules its own 408 on socket read timeout and marks the response committed.
            // Rethrowing here would replace that bounded terminal response with a container 500.
            if (response.isCommitted()) return;
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Connection", "close");
            if (e.getStatus().value() == 429) response.setHeader("Retry-After", "60");
            response.setStatus(e.getStatus().value()); response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), Map.of("code", e.getCode() == null ? "IMPORT_REJECTED" : e.getCode(), "message", e.getMessage()));
        } finally { if (acquired) work.release(principal.id()); }
    }
}
