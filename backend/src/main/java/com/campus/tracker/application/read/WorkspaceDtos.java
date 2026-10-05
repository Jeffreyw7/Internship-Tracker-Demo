package com.campus.tracker.application.read;

import com.campus.tracker.application.ApplicationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WorkspaceDtos {
    private WorkspaceDtos() {}
    public record NextActivity(String id, String kind, String label, LocalDate date, UUID stageId) {}
    public record ApplicationListItem(UUID id, String company, String role, String location, ApplicationStatus status,
            String appliedDate, String deadline, String followUpDate, Instant createdAt, Instant updatedAt,
            long version, boolean sample, NextActivity nextActivity) {}
    public record CursorPage<T>(List<T> content, String nextCursor, boolean hasNext, int size, Instant readAt) {}
    public record WorkspaceSummary(long total, long active, long interviews, long offers, long due,
            long scheduledInterviews, boolean hasSamples, java.util.Map<ApplicationStatus, Long> perStatus, Instant readAt) {}
    public record ActivityItem(String id, UUID applicationId, String company, String role, String location,
            ApplicationStatus applicationStatus, long applicationVersion, String kind, String label, LocalDate date, UUID stageId) {}
    public record ApplicationFilters(String q, ApplicationStatus status, String tab, boolean dueOnly,
            LocalDate today, String sort, int size, String cursor) {
        public ApplicationFilters {
            if (q == null || q.length() > 200 || !List.of("all", "active", "saved", "closed").contains(tab)
                    || !List.of("updated", "company", "deadline", "applied").contains(sort)
                    || size < 1 || size > 100 || (dueOnly && today == null)) throw new IllegalArgumentException("Invalid application filters");
            q = q.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
