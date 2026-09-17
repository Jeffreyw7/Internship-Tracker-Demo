package com.campus.tracker.auth.abuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AuthAbuseFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    private final AuthAbuseProperties policies;
    private final ObjectMapper json;
    public AuthAbuseFilter(AuthRateLimiter limiter, AuthAbuseProperties policies, ObjectMapper json) {
        this.limiter = limiter; this.policies = policies; this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String prefix = null;
        AuthRateLimiter.Policy policy = null;
        if (AuthEndpoints.LOGIN.matches(request)) { prefix = "login-ip:"; policy = policies.loginIp(); }
        else if (AuthEndpoints.REGISTER.matches(request)) { prefix = "register-ip:"; policy = policies.registrationIp(); }
        else if (AuthEndpoints.CSRF.matches(request) && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            prefix = "csrf-ip:"; policy = policies.csrfIp();
        }
        if (policy != null) {
            var decision = limiter.acquire(prefix + request.getRemoteAddr(), policy);
            if (!decision.allowed()) { AuthResponses.throttled(response, json, decision.retryAfterSeconds()); return; }
        }
        chain.doFilter(request, response);
    }
}
