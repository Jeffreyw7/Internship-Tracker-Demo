package com.campus.tracker.application.read;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.tracker.application.read.WorkspaceDtos.*;

@Service
public class ApplicationReadService {
    private final ApplicationReadRepository repository;
    private final ApplicationCursorCodec cursors;
    private final Clock clock;
    public ApplicationReadService(ApplicationReadRepository repository, ApplicationCursorCodec cursors, Clock clock) {
        this.repository = repository; this.cursors = cursors; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public CursorPage<ApplicationListItem> list(UUID ownerId, ApplicationFilters filters) {
        String fingerprint = cursors.fingerprint(ownerId, "applications", filters.q(), filters.status(), filters.tab(),
                filters.dueOnly(), filters.today(), filters.sort(), filters.size());
        var boundary = cursors.decode(filters.cursor(), fingerprint);
        var rows = repository.list(ownerId, filters, boundary);
        boolean more = rows.size() > filters.size();
        var visible = rows.subList(0, Math.min(rows.size(), filters.size()));
        var nextActivities = repository.nextActivities(ownerId, visible.stream().map(row -> row.item().id()).toList());
        var content = visible.stream().map(row -> {
            var item = row.item();
            return new ApplicationListItem(item.id(), item.company(), item.role(), item.location(), item.status(),
                    item.appliedDate(), item.deadline(), item.followUpDate(), item.createdAt(), item.updatedAt(), item.version(),
                    item.sample(), nextActivities.get(item.id()));
        }).toList();
        var last = more ? visible.get(visible.size() - 1) : null;
        return new CursorPage<>(content, last == null ? null : cursors.encode(fingerprint, last.sortValue(), last.item().id()), more, filters.size(), clock.instant());
    }
}
