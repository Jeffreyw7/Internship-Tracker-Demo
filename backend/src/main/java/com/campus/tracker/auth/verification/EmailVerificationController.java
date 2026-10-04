package com.campus.tracker.auth.verification;

import com.campus.tracker.auth.AuthDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/auth/email-verification")
public class EmailVerificationController {
    private final EmailVerificationService verification;
    private final AuthRateLimiter rateLimiter;

    public EmailVerificationController(EmailVerificationService verification, AuthRateLimiter rateLimiter) {
        this.verification = verification; this.rateLimiter = rateLimiter;
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthDtos.Accepted resend(@Valid @RequestBody Resend request, HttpServletRequest http) {
        rateLimiter.requireRequestAllowed(http.getRemoteAddr());
        return verification.resend(request.email());
    }

    @PostMapping("/confirm")
    public AuthDtos.Accepted confirm(@Valid @RequestBody Confirm request, HttpServletRequest http) {
        return verification.confirm(request.token(), request.password(), http.getRemoteAddr());
    }

    public record Resend(@NotBlank @Email @Size(max = 254) String email) {
        @Override public String toString() { return "Resend[redacted]"; }
    }
    public record Confirm(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "Confirm[redacted]"; }
    }
}
