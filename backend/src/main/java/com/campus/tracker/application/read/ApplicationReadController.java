package com.campus.tracker.application.read;

import com.campus.tracker.application.ApplicationStatus;
import com.campus.tracker.auth.CampusPrincipal;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.campus.tracker.application.read.WorkspaceDtos.*;

@RestController
@RequestMapping("/api/v2")
public class ApplicationReadController {
    private final ApplicationReadService applications;
    private final WorkspaceSummaryService summaries;
    private final ActivityReadService activities;
    public ApplicationReadController(ApplicationReadService applications, WorkspaceSummaryService summaries, ActivityReadService activities) {
        this.applications = applications; this.summaries = summaries; this.activities = activities;
    }
    @GetMapping("/applications")
    public CursorPage<ApplicationListItem> list(@AuthenticationPrincipal CampusPrincipal user,
            @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "all") String tab, @RequestParam(defaultValue = "false") boolean dueOnly,
            @RequestParam(required = false) String today, @RequestParam(defaultValue = "updated") String sort,
            @RequestParam(defaultValue = "8") int size, @RequestParam(required = false) String cursor) {
        return applications.list(user.id(), new ApplicationFilters(q, status == null ? null : ApplicationStatus.parse(status),
                tab, dueOnly, today == null ? null : parseDate(today), sort, size, cursor));
    }
    @GetMapping("/applications/summary")
    public WorkspaceSummary summary(@AuthenticationPrincipal CampusPrincipal user, @RequestParam String today) {
        return summaries.summary(user.id(), parseDate(today));
    }
    @GetMapping("/activities")
    public CursorPage<ActivityItem> activities(@AuthenticationPrincipal CampusPrincipal user,
            @RequestParam(required = false) String kind, @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String today, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String cursor) {
        return activities.activities(user.id(), kind, q, today == null ? null : parseDate(today), size, cursor);
    }
    static LocalDate parseDate(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid calendar date");
        try { return LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException failure) { throw new IllegalArgumentException("Invalid calendar date"); }
    }
}
