// Traverse live application history without offset shifts; each request remains its own read transaction.
package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import static com.campus.tracker.application.ApplicationDtos.ApplicationView;

@Service
public class ApplicationHistoryService {
    private final ApplicationRepository repository;
    public ApplicationHistoryService(ApplicationRepository repository) { this.repository = repository; }

    public record HistoryPage(List<ApplicationView> content, String nextCursor) {}

    @Transactional(readOnly = true)
    public HistoryPage list(UUID ownerId, int size, String encodedCursor) {
        if (size < 1 || size > 100) throw invalidCursor();
        Cursor cursor = encodedCursor == null ? null : Cursor.decode(encodedCursor, ownerId);
        Key upper;
        if (cursor == null) {
            var newest = repository.findFirstByOwnerIdAndDeletedFalseOrderByCreatedAtDescIdDesc(ownerId);
            if (newest.isEmpty()) return new HistoryPage(List.of(), null);
            upper = Key.of(newest.get());
        } else upper = cursor.upper();

        // One look-ahead row determines completion without a count that concurrent writes can invalidate.
        var limit = PageRequest.of(0, size + 1);
        List<InternshipApplication> rows = cursor == null
                ? repository.findHistoryFirst(ownerId, upper.createdAt(), upper.id(), limit)
                : repository.findHistoryAfter(ownerId, cursor.after().createdAt(), cursor.after().id(), upper.createdAt(), upper.id(), limit);
        boolean more = rows.size() > size;
        List<InternshipApplication> content = more ? rows.subList(0, size) : rows;
        String next = more ? new Cursor(ownerId, upper, Key.of(content.get(content.size() - 1))).encode() : null;
        // Edits/deletes/restores can be observed between pages. The fixed upper key excludes later keys,
        // but this is a traversal of live records, not a point-in-time database snapshot.
        return new HistoryPage(content.stream().map(ApplicationView::from).toList(), next);
    }

    private static ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "The history cursor or page size is invalid. Refresh and try again.");
    }

    private record Key(Instant createdAt, UUID id) implements Comparable<Key> {
        static Key of(InternshipApplication application) { return new Key(application.createdAt, application.id); }
        @Override public int compareTo(Key other) {
            int timestamp = createdAt.compareTo(other.createdAt);
            // UUID's Java comparison is signed; database UUID order follows the unsigned textual value.
            return timestamp != 0 ? timestamp : id.toString().compareTo(other.id.toString());
        }
    }

    // The client must treat this versioned, owner-bound continuation as opaque. It is not an access token:
    // every query independently scopes to the authenticated owner even if a cursor is manually modified.
    private record Cursor(UUID ownerId, Key upper, Key after) {
        String encode() {
            String value = "1|" + ownerId + "|" + upper.createdAt() + "|" + upper.id() + "|" + after.createdAt() + "|" + after.id();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
        }

        static Cursor decode(String value, UUID ownerId) {
            if (value.isEmpty() || value.length() > 512 || !value.matches("[A-Za-z0-9_-]+")) throw invalidCursor();
            try {
                String[] fields = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split("\\|", -1);
                if (fields.length != 6 || !fields[0].equals("1") || !fields[1].equals(ownerId.toString())) throw invalidCursor();
                Cursor cursor = new Cursor(ownerId, key(fields[2], fields[3]), key(fields[4], fields[5]));
                if (cursor.after().compareTo(cursor.upper()) >= 0) throw invalidCursor();
                return cursor;
            } catch (IllegalArgumentException | DateTimeException e) { throw invalidCursor(); }
        }

        private static Key key(String timestamp, String id) {
            Instant instant = Instant.parse(timestamp);
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id) || instant.getNano() % 1000 != 0
                    || instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) throw invalidCursor();
            return new Key(instant, uuid);
        }
    }
}
