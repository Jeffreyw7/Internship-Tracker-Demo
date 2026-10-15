package com.campus.tracker.application.importing;

import com.campus.tracker.application.ApplicationDtos.ApplicationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

/** Explicit wire whitelist. Source-bearing values deliberately have redacted diagnostic strings. */
public final class ImportDtos {
    private ImportDtos() {}
    public record Options(int schemaVersion, Map<String,String> mapping, String dateFormat,
                          List<RowOverride> rows, UUID supersedesPreviewId) {
        @Override public String toString() { return "ImportOptions[redacted]"; }
    }
    public record RowOverride(int rowNumber, String action, UUID targetId, Map<String,String> values,
                              List<String> clears, String stageMode, boolean acknowledgeLegacy) {
        @Override public String toString() { return "ImportRowOverride[redacted]"; }
    }
    public record Issue(String code, String field, String message) {}
    public record Counts(int added, int updated, int skipped, int unchanged, int errors) {}
    public record Candidate(UUID id, String company, String role, String location) {
        @Override public String toString() { return "ImportCandidate[redacted]"; }
    }
    public record Intent(String intent, String value) {
        @Override public String toString() { return "ImportIntent[redacted]"; }
    }
    public record Difference(String field, String before, String after) {
        @Override public String toString() { return "ImportDifference[redacted]"; }
    }
    public record PreviewRow(int rowNumber, List<String> cells, String action, UUID targetId, Long targetVersion,
                             Candidate targetSummary, List<Candidate> candidates, boolean moreCandidates,
                             Map<String,Intent> intents, ApplicationRequest before, ApplicationRequest after,
                             List<Difference> differences, List<Issue> issues, List<String> warnings) {
        @Override public String toString() { return "ImportPreviewRow[redacted]"; }
    }
    public record Preview(UUID id, int schemaVersion, String policyVersion, Instant createdAt, Instant expiresAt,
                          String reviewDigest, boolean committable, List<String> headers, List<PreviewRow> rows,
                          Counts counts, List<Issue> issues) {
        @Override public String toString() { return "ImportPreview[redacted]"; }
    }
    public record Acknowledgement(@NotNull @Min(0) @Max(500) Integer existingApplicationsToUpdate) {}
    public record CommitRequest(@NotBlank @Size(max=64) String reviewDigest,
                                @NotNull @Valid Acknowledgement acknowledgement) {}
    public record ReceiptRow(int rowNumber, String action, UUID applicationId, Long resultVersion) {}
    public record Receipt(UUID importId, UUID receiptId, Instant completedAt, Instant retainUntil,
                          Counts counts, List<ReceiptRow> rows) {}
    public record Status(String status, Preview preview, Receipt receipt, UUID successorId, String code) {}
    /** Stored alongside the review so the digest binds every review-affecting option. */
    public record CanonicalPreview(Preview preview, Options options, String sourceDigest) {
        @Override public String toString() { return "CanonicalImportPreview[redacted]"; }
    }
}
