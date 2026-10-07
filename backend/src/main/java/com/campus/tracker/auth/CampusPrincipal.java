// Represents the authenticated account inside the server-side Spring Security session.
package com.campus.tracker.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record CampusPrincipal(UUID id, String displayName, String email, String passwordHash,
                              boolean emailAccessAllowed) implements UserDetails {
    // Copy the database account into the UserDetails shape required by authentication.
    public static CampusPrincipal from(CampusUser user) {
        return new CampusPrincipal(user.getId(), user.getDisplayName(), user.getEmail(), user.getPasswordHash(),
                user.canAuthenticate());
    }
    // All accounts currently share the ordinary user role.
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_USER")); }
    // Supply the stored hash to the authentication provider for password verification.
    @Override public String getPassword() { return passwordHash; }
    // Use the email address as the login identifier.
    @Override public String getUsername() { return email; }
    // Account expiry, locking, and disabling are not currently modeled; these flags allow login.
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
