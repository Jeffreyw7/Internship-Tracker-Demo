// Defines the registration input and safe public account response used by the authentication API.
package com.campus.tracker.auth;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}
    // Apply basic field bounds before the service checks password complexity and byte length.
    public record Registration(
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password,
            boolean includeSamples) {}
    // Expose only public profile fields; never serialize the password hash in a response.
    public record UserView(UUID id, String displayName, String email) {
        public static UserView from(CampusPrincipal p) { return new UserView(p.id(), p.displayName(), p.email()); }
    }
}
