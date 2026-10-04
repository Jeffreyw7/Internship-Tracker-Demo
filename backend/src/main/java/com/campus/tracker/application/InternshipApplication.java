// Maps an internship application and its ordered interview stages to the relational database.
package com.campus.tracker.application;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "applications")
public class InternshipApplication {
    // Use a generated record ID and immutable owner ID to identify the user\'s application.
    @Id UUID id = UUID.randomUUID();
    @Column(name = "owner_id", nullable = false, updatable = false) UUID ownerId;
    // Store the editable opportunity details, dates, and notes.
    @Column(nullable = false, length = 100) String company;
    @Column(nullable = false, length = 180) String role;
    @Column(nullable = false, length = 120) String location;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) ApplicationStatus status;
    @Column(name = "applied_date") LocalDate appliedDate;
    LocalDate deadline;
    @Column(name = "follow_up_date") LocalDate followUpDate;
    @Column(nullable = false, length = 10000) String notes;
    // Track illustrative data and soft deletion independently from the application status.
    @Column(nullable = false) boolean sample;
    @Column(nullable = false) boolean deleted;
    // Let JPA detect competing writes with an automatically incremented version.
    @Version @Column(nullable = false) long version;
    // Use microsecond precision to match database timestamp round-trips.
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @Column(name = "updated_at", nullable = false) Instant updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    // Persist stages with their parent, delete removed stages, and batch collection reads.
    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC") @BatchSize(size = 50)
    List<InterviewStage> stages = new ArrayList<>();
    // JPA uses the empty constructor; application code supplies the authenticated owner.
    protected InternshipApplication() {}
    InternshipApplication(UUID ownerId) { this.ownerId = ownerId; }
}
