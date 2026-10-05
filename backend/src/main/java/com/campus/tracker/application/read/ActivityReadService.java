package com.campus.tracker.application.read;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.tracker.application.read.WorkspaceDtos.*;

@Service
public class ActivityReadService {
    static final List<String> KINDS = List.of("Interview", "Follow-up", "Deadline");
    private final ApplicationReadRepository repository;
    private final ApplicationCursorCodec cursors;
    private final Clock clock;
    public ActivityReadService(ApplicationReadRepository repository, ApplicationCursorCodec cursors, Clock clock) {
        this.repository = repository; this.cursors = cursors; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public CursorPage<ActivityItem> activities(UUID ownerId, String kind, String q, LocalDate today, int size, String cursor) {
        if ((kind != null && !KINDS.contains(kind)) || q == null || q.length() > 200 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid activity filters");
        q = q.strip().toLowerCase(Locale.ROOT);
        String fingerprint = cursors.fingerprint(ownerId, "activities", kind, q, today, size);
        var boundary = cursors.decode(cursor, fingerprint);
        LocalDate lastDate = null; String lastKind = null; String lastId = null;
        if (boundary != null) {
            if (boundary.value() == null) throw new IllegalArgumentException("Invalid activity cursor");
            String[] tuple = boundary.value().split("\\|", -1);
            if (tuple.length != 2 || !KINDS.contains(tuple[1])) throw new IllegalArgumentException("Invalid activity cursor");
            lastDate = ApplicationReadController.parseDate(tuple[0]); lastKind = tuple[1];
            lastId = boundary.id().toString() + (lastKind.equals("Follow-up") ? "-follow" : lastKind.equals("Deadline") ? "-deadline" : "");
        }
        var rows = repository.activities(ownerId, kind, q, size + 1, lastDate, lastKind, lastId);
        boolean more = rows.size() > size;
        var content = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        String next = null;
        if (more) {
            var last = content.get(content.size() - 1);
            next = cursors.encode(fingerprint, last.date() + "|" + last.kind(), last.stageId() == null ? last.applicationId() : last.stageId());
        }
        return new CursorPage<>(content, next, more, size, clock.instant());
    }
}
