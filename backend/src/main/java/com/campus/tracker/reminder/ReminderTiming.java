package com.campus.tracker.reminder;

import java.time.*;

/** Date-only source events are delivered at an explicit time in the account's zone. */
public final class ReminderTiming {
    private ReminderTiming() {}
    public static Instant due(LocalDate date, int leadDays, String time, String timezone) {
        return date.minusDays(leadDays).atTime(LocalTime.parse(time)).atZone(ZoneId.of(timezone)).toInstant();
    }
}
