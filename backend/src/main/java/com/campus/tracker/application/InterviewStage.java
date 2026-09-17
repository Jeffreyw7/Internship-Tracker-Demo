package com.campus.tracker.application;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "interview_stages")
public class InterviewStage {
    @Id UUID id = UUID.randomUUID();
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false) InternshipApplication application;
    @Column(nullable = false) int position;
    @Column(nullable = false, length = 120) String name;
    @Column(name = "stage_date") LocalDate date;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) StageState state;
    protected InterviewStage() {}
    InterviewStage(InternshipApplication application) { this.application = application; }
}
