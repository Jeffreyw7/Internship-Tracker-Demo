// Creates optional illustrative applications for a newly registered account.
package com.campus.tracker.application;

import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static com.campus.tracker.application.ApplicationDtos.*;
import static com.campus.tracker.application.ApplicationStatus.*;

@Service
public class SampleDataService {
    private final ApplicationService applications;
    // Reuse the regular application service so samples follow the same business rules.
    public SampleDataService(ApplicationService applications) { this.applications = applications; }

    // Build ten examples with dates relative to today and stages for interviewing applications.
    public void seed(UUID owner) {
        record Sample(String company, ApplicationStatus status, int applied, Integer deadline, Integer follow) {}
        var samples = List.of(new Sample("Figma", INTERVIEWING, -8, 21, 2),
                new Sample("Stripe", APPLIED, -5, 14, 0), new Sample("Bloomberg", INTERVIEWING, -14, 10, 5),
                new Sample("Notion", SAVED, 0, 3, null), new Sample("Datadog", APPLIED, -3, 18, 4),
                new Sample("MongoDB", INTERVIEWING, -18, 12, null), new Sample("Spotify", APPLIED, -7, 14, -1),
                new Sample("Vercel", OFFER, -26, null, 6), new Sample("Adobe", REJECTED, -21, null, null),
                new Sample("Ramp", APPLIED, -2, 20, 7));
        // Stagger upcoming technical interviews across the seeded examples.
        int interviewOffset = 1;
        for (Sample s : samples) {
            List<StageRequest> stages = s.status() == INTERVIEWING ? List.of(
                    new StageRequest(null, "Recruiter screen", date(-3), StageState.COMPLETED),
                    new StageRequest(null, "Technical interview", date(interviewOffset++), StageState.SCHEDULED),
                    new StageRequest(null, "Final interview", null, StageState.PLANNED)) : List.of();
            applications.create(owner, new ApplicationRequest(s.company(), "Software Engineer Intern",
                    s.company().equals("Vercel") ? "Remote" : "New York, NY", s.status(),
                    s.status() == SAVED ? null : date(s.applied()), date(s.deadline()), date(s.follow()),
                    "Illustrative sample application. Prepare to explain your Java API, database design, and project trade-offs.", stages), true);
        }
    }
    // Convert optional relative offsets to dates while preserving missing dates.
    private static LocalDate date(Integer offset) { return offset == null ? null : LocalDate.now().plusDays(offset); }
}
