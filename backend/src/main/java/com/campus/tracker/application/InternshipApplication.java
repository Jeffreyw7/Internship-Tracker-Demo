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
    @Id UUID id = UUID.randomUUID();
    @Column(name = "owner_id", nullable = false, updatable = false) UUID ownerId;
    @Column(nullable = false, length = 100) String company;
    @Column(nullable = false, length = 180) String role;
    @Column(nullable = false, length = 120) String location;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) ApplicationStatus status;
    @Column(name = "applied_date") LocalDate appliedDate;
    LocalDate deadline;
    @Column(name = "follow_up_date") LocalDate followUpDate;
    @Column(nullable = false, length = 10000) String notes;
    @Column(nullable = false) boolean sample;
    @Column(nullable = false) boolean deleted;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @Column(name = "updated_at", nullable = false) Instant updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @OneToMany(mappedBy = "application", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC") @BatchSize(size = 50)
    List<InterviewStage> stages = new ArrayList<>();
    protected InternshipApplication() {}
    InternshipApplication(UUID ownerId) { this.ownerId = ownerId; }
}
