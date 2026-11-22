package com.campus.tracker.application.importing;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authorization precedes durable rates, transport checks and process permits. */
public final class ImportRequestFilter extends OncePerRequestFilter {
    private static final Logger ingress = LoggerFactory.getLogger("campus.ingress.imports");
    private static final Pattern RENDER_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}(?:-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})?");
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
        boolean preview = ImportUploadPolicy.isPreview(request);
        boolean commit = ImportUploadPolicy.isCommit(request);
        String operation = "GET".equals(request.getMethod()) ? "status" : preview ? "preview" : "commit";
        IngressReceipt receipt = preview || commit ? IngressReceipt.capture(request) : null;
        Instant admittedAt = null;
        boolean acquired = false;
        try {
            if (preview) ImportUploadPolicy.checkTransport(request, limits);
            if (commit) ImportUploadPolicy.checkConfirmationTransport(request);
            try { admission.checkRate(principal.id(), operation); }
            catch (DataAccessException e) { throw ImportUploadPolicy.error(HttpStatus.TOO_MANY_REQUESTS,
                    "IMPORT_RATE_UNAVAILABLE", "Import admission is temporarily unavailable. Try again shortly."); }
            if (preview || commit) {
                work.acquire(principal.id()); acquired = true;
                ImportUploadPolicy.begin(request, limits);
                if (receipt != null) admittedAt = Instant.now();
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
        } finally {
            if (acquired) work.release(principal.id());
            if (receipt != null) receipt.log(operation, admittedAt, acquired ? Instant.now() : null, response.getStatus());
        }
    }

    private record IngressReceipt(String requestId, Instant receivedAt, boolean http11, boolean forwardedPresent,
            boolean cfConnectingIpPresent, boolean xForwardedForPresent, boolean forwardedForTailMatchesCfIp, boolean protoHeaderIsHttps) {
        static IngressReceipt capture(HttpServletRequest request) {
            if (!ingress.isDebugEnabled()) return null;
            String id = request.getHeader("Rndr-Id");
            if (id == null || !RENDER_ID.matcher(id).matches()) return null;
            Instant receivedAt = Instant.now();
            String cfIp = request.getHeader("CF-Connecting-IP");
            var forwardedValues = request.getHeaders("X-Forwarded-For");
            String lastValue = null;
            while (forwardedValues != null && forwardedValues.hasMoreElements()) lastValue = forwardedValues.nextElement();
            boolean tailMatches = cfIp != null && !cfIp.isBlank() && lastValue != null
                    && cfIp.equalsIgnoreCase(lastValue.substring(lastValue.lastIndexOf(',') + 1).trim());
            return new IngressReceipt(id, receivedAt, "HTTP/1.1".equals(request.getProtocol()),
                    request.getHeader("Forwarded") != null, cfIp != null, lastValue != null, tailMatches,
                    "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
        }

        void log(String operation, Instant admittedAt, Instant releasedAt, int servletStatus) {
            ingress.debug("requestId={} operation={} filterReceivedAt={} admittedAt={} releasedAt={} servletStatus={} "
                            + "http11={} forwardedPresent={} cfConnectingIpPresent={} xForwardedForPresent={} forwardedForTailMatchesCfIp={} protoHeaderIsHttps={}",
                    requestId, operation, receivedAt, admittedAt, releasedAt, servletStatus, http11,
                    forwardedPresent, cfConnectingIpPresent, xForwardedForPresent, forwardedForTailMatchesCfIp, protoHeaderIsHttps);
        }
    }
}
