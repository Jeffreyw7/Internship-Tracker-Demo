package com.campus.tracker.auth.abuse;

import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@SuppressWarnings("deprecation")
final class AuthEndpoints {
    // Same servletPath/pathInfo matching used by Spring form login.
    static final RequestMatcher LOGIN = new AntPathRequestMatcher("/api/auth/login", "POST");
    static final RequestMatcher REGISTER = new AntPathRequestMatcher("/api/auth/register", "POST");
    static final RequestMatcher CSRF = new AntPathRequestMatcher("/api/auth/csrf");
    private AuthEndpoints() {}
}
