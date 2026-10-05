package com.campus.tracker.application.read;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.tracker.application.read.WorkspaceDtos.*;

@Service
public class WorkspaceSummaryService {
    private final ApplicationReadRepository repository;
    private final Clock clock;
    public WorkspaceSummaryService(ApplicationReadRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    @Transactional(readOnly = true)
    public WorkspaceSummary summary(UUID ownerId, LocalDate today) {
        if (today == null) throw new IllegalArgumentException("A calendar date is required");
        return repository.summary(ownerId, today, clock.instant());
    }
}
