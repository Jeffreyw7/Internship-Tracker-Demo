package com.campus.tracker.auth.abuse;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

final class AuthEndpoints {
    // Match exact application paths relative to the context path; retain the original method restrictions.
    static final RequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login");
    static final RequestMatcher REGISTER = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/register");
    static final RequestMatcher CSRF = PathPatternRequestMatcher.withDefaults().matcher("/api/auth/csrf");
    private AuthEndpoints() {}
}
