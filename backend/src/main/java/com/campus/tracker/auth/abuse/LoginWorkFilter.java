package com.campus.tracker.auth.abuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LoginWorkFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    private final AuthWorkLimiter work;
    private final AuthAbuseProperties policies;
    private final ObjectMapper json;
    public LoginWorkFilter(AuthRateLimiter limiter, AuthWorkLimiter work, AuthAbuseProperties policies, ObjectMapper json) {
        this.limiter = limiter; this.work = work; this.policies = policies; this.json = json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!AuthEndpoints.LOGIN.matches(request)) { chain.doFilter(request, response); return; }
        String[] emails = request.getParameterValues("email");
        if (emails == null || emails.length != 1 || emails[0].length() > 254 || emails[0].isBlank()) {
            AuthResponses.error(response, json, 400, "Provide one email address of at most 254 characters."); return;
        }
        String email = emails[0].trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254) { AuthResponses.error(response, json, 400, "Invalid email address."); return; }
        var decision = limiter.acquire("login-email:" + email, policies.loginEmail());
        if (!decision.allowed()) { AuthResponses.throttled(response, json, decision.retryAfterSeconds()); return; }
        var permit = work.tryAcquire();
        if (permit == null) { AuthResponses.throttled(response, json, 1); return; }
        try (permit) { chain.doFilter(request, response); }
    }
}
