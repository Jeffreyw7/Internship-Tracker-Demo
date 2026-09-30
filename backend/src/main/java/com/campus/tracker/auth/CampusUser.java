// Maps a persisted account to campus_users, keeping password hashes separate from API response objects.
package com.campus.tracker.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campus_users")
public class CampusUser {
    // Persist account identity, unique normalized email, display name, and hashed credentials.
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "display_name", nullable = false, length = 80) private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "theme_preference", nullable = false, length = 6) private String themePreference = "system";
    @Column(name = "email_verified_at") private Instant emailVerifiedAt;
    @Column(name = "email_verification_exempt", nullable = false) private boolean emailVerificationExempt;

    // Provide the no-argument constructor JPA needs when loading database rows.
    protected CampusUser() {}
    // Assign a server-generated ID and use the database timestamp precision on creation.
    public CampusUser(String email, String displayName, String passwordHash) {
        this.id = UUID.randomUUID(); this.email = email; this.displayName = displayName;
        this.passwordHash = passwordHash; this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.emailVerificationExempt = false;
    }
    // Expose stored fields to the service and security principal mapping.
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public String getThemePreference() { return themePreference; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public boolean isEmailVerificationExempt() { return emailVerificationExempt; }
    public boolean canAuthenticate() { return emailVerifiedAt != null || emailVerificationExempt; }
    public void verifyEmail(Instant verifiedAt) { this.emailVerifiedAt = verifiedAt; }
}
