// Stores one ordered interview step belonging to exactly one application.
package com.campus.tracker.application;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "interview_stages")
public class InterviewStage {
    @Id UUID id = UUID.randomUUID();
    // Load the parent lazily and enforce the required foreign-key relationship.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false) InternshipApplication application;
    // Persist display order independently from the stage name, date, and completion state.
    @Column(nullable = false) int position;
    @Column(nullable = false, length = 120) String name;
    @Column(name = "stage_date") LocalDate date;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) StageState state;
    // Support JPA loading and creation of a stage attached to its parent.
    protected InterviewStage() {}
    InterviewStage(InternshipApplication application) { this.application = application; }
}
