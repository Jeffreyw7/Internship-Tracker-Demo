package com.campus.tracker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

/** Uncached primary-database epochs revoke sessions before CSRF and protected API dispatch. */
@SuppressWarnings("deprecation")
public final class CredentialVersionFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate reads;

    public CredentialVersionFilter(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(2);
    }
    public boolean isCurrent(CampusPrincipal principal) {
        if (principal.credentialVersion() == null || principal.credentialVersion() < 0) return false;
        return Boolean.TRUE.equals(reads.execute(status -> {
            var versions = jdbc.queryForList("SELECT credential_version FROM campus_users WHERE id=?", Long.class, principal.id());
            return versions.size() == 1 && principal.credentialVersion().equals(versions.get(0));
        }));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !new AntPathRequestMatcher("/api/**").matches(request);
    }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            boolean current;
            try {
                current = authentication.getPrincipal() instanceof CampusPrincipal principal && isCurrent(principal);
            } catch (RuntimeException unavailable) {
                respond(response, 503, "Authentication is temporarily unavailable. Please try again.");
                return;
            }
            if (!current) {
                discardSession(request);
                if (!publicAuthentication(request)) {
                    respond(response, 401, "Sign in to continue.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
    public static void discardSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        // A delayed passive response must never delete a newer browser session's HttpOnly cookie.
    }
    private boolean publicAuthentication(HttpServletRequest request) {
        if (new AntPathRequestMatcher("/api/auth/csrf").matches(request)) return true;
        if (new AntPathRequestMatcher("/api/health", "GET").matches(request)
                || new AntPathRequestMatcher("/api/health", "HEAD").matches(request)) return true;
        for (String path : new String[]{"/api/auth/login", "/api/auth/register", "/api/auth/email-verification/resend",
                "/api/auth/email-verification/confirm", "/api/auth/password-reset/request", "/api/auth/password-reset/confirm"}) {
            if (new AntPathRequestMatcher(path, "POST").matches(request)) return true;
        }
        return false;
    }
    public void respond(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
