package com.campus.tracker.auth.verification;

import com.campus.tracker.auth.AuthDtos;
import com.campus.tracker.auth.abuse.AuthThrottleException;
import com.campus.tracker.auth.abuse.AuthWorkLimiter;
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
    private final AuthWorkLimiter work;

    public EmailVerificationController(EmailVerificationService verification, AuthRateLimiter rateLimiter, AuthWorkLimiter work) {
        this.verification = verification; this.rateLimiter = rateLimiter; this.work = work;
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthDtos.Accepted resend(@Valid @RequestBody Resend request, HttpServletRequest http) {
        rateLimiter.requireRequestAllowed(http.getRemoteAddr());
        var permit = work.tryAcquire();
        if (permit == null) throw new AuthThrottleException(1);
        // Limit hashing before entering the service transaction, just as registration does.
        try (permit) { return verification.resend(request.email()); }
    }

    @PostMapping("/confirm")
    public AuthDtos.Accepted confirm(@Valid @RequestBody Confirm request, HttpServletRequest http) {
        var permit = work.tryAcquire();
        if (permit == null) throw new AuthThrottleException(1);
        try (permit) { return verification.confirm(request.token(), request.password(), http.getRemoteAddr()); }
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
