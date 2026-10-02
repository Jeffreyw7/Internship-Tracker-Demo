package com.campus.tracker.reminder;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReminderDtos {
    private ReminderDtos() {}
    public enum DeliveryMode { IMMEDIATE, DAILY_DIGEST }
    public record Preferences(
        @NotNull Boolean enabled, @NotNull Boolean interviewsEnabled,
        @NotNull Boolean deadlinesEnabled, @NotNull Boolean followUpsEnabled,
        @NotNull @Min(0) @Max(30) Integer interviewLeadDays,
        @NotNull @Min(0) @Max(30) Integer deadlineLeadDays,
        @NotNull @Min(0) @Max(30) Integer followUpLeadDays,
        @NotBlank @Size(max=100) String timezone,
        @NotNull @Pattern(regexp="([01][0-9]|2[0-3]):[0-5][0-9]") String reminderTime,
        @NotNull DeliveryMode deliveryMode,
        @NotNull @Pattern(regexp="([01][0-9]|2[0-3]):[0-5][0-9]") String digestTime,
        @NotNull Boolean emailEnabled) {}
    public record PreferenceView(boolean enabled, boolean interviewsEnabled, boolean deadlinesEnabled,
        boolean followUpsEnabled, int interviewLeadDays, int deadlineLeadDays, int followUpLeadDays,
        String timezone, String reminderTime, DeliveryMode deliveryMode, String digestTime, boolean emailEnabled, long version) {}
    public record ReminderView(UUID id, UUID applicationId, String kind, String company, String role,
        String label, String eventDate, Instant dueAt, String state, boolean snoozed, long version, String emailStatus) {}
    public record Overview(PreferenceView preferences, boolean emailAvailable, List<ReminderView> reminders, int readyCount) {}
    public record Snooze(@NotNull @Min(15) @Max(10080) Integer minutes) {}
}
