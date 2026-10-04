// Exposes signup, CSRF-token retrieval, and the current profile; Spring Security handles login and logout.
package com.campus.tracker.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts;
    public AuthController(AccountService accounts) { this.accounts = accounts; }

    // Return the session CSRF token and the header name required for write requests.
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }

    // Read identity from the authenticated session rather than from client-supplied fields.
    @GetMapping("/me")
    public AuthDtos.UserView me(@AuthenticationPrincipal CampusPrincipal user) { return AuthDtos.UserView.from(user); }

    // Validate incoming JSON before delegating account creation to the service.
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.UserView register(@Valid @RequestBody AuthDtos.Registration request) { return accounts.register(request); }
}
