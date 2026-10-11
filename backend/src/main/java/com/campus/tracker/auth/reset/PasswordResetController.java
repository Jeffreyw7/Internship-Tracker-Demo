package com.campus.tracker.auth.reset;

import com.campus.tracker.auth.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {
    private final PasswordResetService resets;
    public PasswordResetController(PasswordResetService resets) { this.resets=resets; }
    public record Request(@NotBlank @Email @Size(max=254) String email) {
        @Override public String toString() { return "ResetRequest[redacted]"; }
    }
    public record Confirmation(String token,String newPassword) {
        @Override public String toString() { return "ResetConfirmation[redacted]"; }
    }
    @PostMapping("/request")
    public ResponseEntity<AuthDtos.Accepted> request(@Valid @RequestBody Request body,HttpServletRequest request) {
        return ResponseEntity.accepted().header("Cache-Control","no-store").body(resets.request(body.email(),request.getRemoteAddr()));
    }
    @PostMapping("/confirm")
    public ResponseEntity<AuthDtos.Accepted> confirm(@RequestBody Confirmation body,HttpServletRequest request) {
        var completed=resets.confirm(body.token(),body.newPassword(),request.getRemoteAddr());
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth!=null && auth.getPrincipal() instanceof CampusPrincipal principal
                && principal.id().equals(completed.userId())) {
            var session=request.getSession(false);if (session!=null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(completed.response());
    }
}
