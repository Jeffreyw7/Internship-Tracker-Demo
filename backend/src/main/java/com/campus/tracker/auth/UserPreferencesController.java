package com.campus.tracker.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// Identity comes exclusively from the authenticated session. Existing API security enforces CSRF.
@RestController
@RequestMapping("/api/me/preferences")
public class UserPreferencesController {
    public record Preferences(@NotNull @Pattern(regexp = "system|light|dark") String themePreference) {}
    private final UserPreferencesService preferences;
    public UserPreferencesController(UserPreferencesService preferences) { this.preferences = preferences; }

    @GetMapping
    public Preferences read(@AuthenticationPrincipal CampusPrincipal user) {
        return new Preferences(preferences.read(user.id()));
    }

    @PutMapping
    public Preferences write(@AuthenticationPrincipal CampusPrincipal user, @Valid @RequestBody Preferences input) {
        return new Preferences(preferences.write(user.id(), input.themePreference()));
    }
}
