// Exposes signup, CSRF-token retrieval, and the current profile; Spring Security handles login and logout.
package com.campus.tracker.auth;

import com.campus.tracker.auth.abuse.AuthThrottleException;
import com.campus.tracker.auth.abuse.AuthWorkLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import com.campus.tracker.auth.verification.AuthRateLimiter;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts;
    private final AuthWorkLimiter work;
    private final AuthRateLimiter rateLimiter;
    public AuthController(AccountService accounts, AuthWorkLimiter work, AuthRateLimiter rateLimiter) {
        this.accounts = accounts; this.work = work; this.rateLimiter = rateLimiter;
    }

    // Return the session CSRF token and the header name required for write requests.
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }

    // Read identity from the authenticated session rather than from client-supplied fields.
    @GetMapping("/me")
    public AuthDtos.UserView me(@AuthenticationPrincipal CampusPrincipal user) { return AuthDtos.UserView.from(user); }

    // Validate incoming JSON before delegating account creation to the service.
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthDtos.Accepted register(@Valid @RequestBody AuthDtos.Registration request, HttpServletRequest http) {
        rateLimiter.requireRequestAllowed(http.getRemoteAddr());
        var permit = work.tryAcquire();
        if (permit == null) throw new AuthThrottleException(1);
        // Acquire before entering the transactional service so rejected work holds no database connection.
        try (permit) { return accounts.register(request); }
    }
}
