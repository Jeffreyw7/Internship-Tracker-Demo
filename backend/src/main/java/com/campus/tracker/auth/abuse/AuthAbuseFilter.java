package com.campus.tracker.auth.abuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AuthAbuseFilter extends OncePerRequestFilter {
    private final AuthAdmissionLimiter limiter;
    private final AuthAbuseProperties policies;
    private final ObjectMapper json;
    public AuthAbuseFilter(AuthAdmissionLimiter limiter, AuthAbuseProperties policies, ObjectMapper json) {
        this.limiter = limiter; this.policies = policies; this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
        boolean tokenEndpoint = AuthEndpoints.CSRF.matches(request)
                && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
        // CsrfFilter allocates a token/session before rejecting ANY anonymous unsafe request,
        // including unmapped URLs and wrong methods. Budget that allocation before it happens.
        if ((tokenEndpoint || (anonymous && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)))
                && !admit("csrf-ip:", policies.csrfIp(), request, response)) return;
        if (AuthEndpoints.LOGIN.matches(request)
                && !admit("login-ip:", policies.loginIp(), request, response)) return;
        if (AuthEndpoints.REGISTER.matches(request)
                && !admit("register-ip:", policies.registrationIp(), request, response)) return;
        chain.doFilter(request, response);
    }
    private boolean admit(String prefix, AuthRateLimiter.Policy policy, HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        var decision = limiter.acquire(prefix + request.getRemoteAddr(), policy);
        if (decision.allowed()) return true;
        AuthResponses.throttled(response, json, decision.retryAfterSeconds());
        return false;
    }
}
