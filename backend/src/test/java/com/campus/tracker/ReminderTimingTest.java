package com.campus.tracker;

import com.campus.tracker.reminder.ReminderTiming;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReminderTimingTest {
    @Test void localDatesRespectLeadTimeAndDaylightSavingChanges() {
        assertThat(ReminderTiming.due(LocalDate.parse("2026-03-09"),1,"09:00","America/New_York"))
            .isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
        assertThat(ReminderTiming.due(LocalDate.parse("2026-03-08"),1,"09:00","America/New_York"))
            .isEqualTo(Instant.parse("2026-03-07T14:00:00Z"));
        assertThat(ReminderTiming.due(LocalDate.parse("2026-03-08"),0,"02:30","America/New_York"))
            .isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(ReminderTiming.due(LocalDate.parse("2026-11-01"),0,"01:30","America/New_York"))
            .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }
}
