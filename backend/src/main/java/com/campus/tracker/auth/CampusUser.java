package com.campus.tracker.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campus_users")
public class CampusUser {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "display_name", nullable = false, length = 80) private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected CampusUser() {}
    public CampusUser(String email, String displayName, String passwordHash) {
        this.id = UUID.randomUUID(); this.email = email; this.displayName = displayName;
        this.passwordHash = passwordHash; this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
}
