package com.campus.tracker.application;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity @Table(name="posting_description_snapshots")
class PostingDescriptionSnapshot {
    @Id UUID id=UUID.randomUUID();
    @Column(name="application_id",nullable=false,updatable=false) UUID applicationId;
    @Column(nullable=false,updatable=false) int revision;
    @Column(name="description_text",nullable=false,updatable=false,columnDefinition="text") String descriptionText;
    @Column(name="source_url",nullable=false,updatable=false,length=2048) String sourceUrl;
    @Column(name="captured_at",nullable=false,updatable=false) Instant capturedAt=Instant.now().truncatedTo(ChronoUnit.MICROS);
    @Column(name="capture_method",nullable=false,updatable=false,length=20) String captureMethod="MANUAL";
    protected PostingDescriptionSnapshot() {}
    PostingDescriptionSnapshot(InternshipApplication a,String text) {
        applicationId=a.id; revision=a.descriptionRevision; descriptionText=text; sourceUrl=a.postingDetails.postingUrl;
    }
}
