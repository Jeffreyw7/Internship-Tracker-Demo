package com.campus.tracker.auth;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}
    public record Registration(
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            boolean includeSamples) {}
    public record UserView(UUID id, String displayName, String email) {
        public static UserView from(CampusPrincipal p) { return new UserView(p.id(), p.displayName(), p.email()); }
    }
}
