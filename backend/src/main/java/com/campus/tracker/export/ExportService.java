package com.campus.tracker.export;

import com.campus.tracker.common.ApiException;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.tracker.export.ExportDtos.*;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "campus.export.enabled", havingValue = "true", matchIfMissing = true)
public class ExportService {
    private final ExportRepository repository;
    private final ExportProperties limits;
    private final ExportObjectStore objects;
    private final Clock clock;
    public ExportService(ExportRepository repository, ExportProperties limits, ExportObjectStore objects, Clock clock) {
        this.repository = repository; this.limits = limits; this.objects = objects; this.clock = clock;
    }
    @Transactional public ExportView create(UUID owner) { return view(repository.create(owner, clock.instant(), limits)); }
    @Transactional(readOnly = true) public ExportView get(UUID owner, UUID id) { return view(repository.owned(owner, id)); }
    @Transactional public ExportView cancel(UUID owner, UUID id) {
        repository.owned(owner, id); repository.cancel(owner, id); return view(repository.owned(owner, id));
    }
    private ExportView view(ExportRepository.Job job) {
        var state = job.expiresAt().isAfter(clock.instant()) ? job.state() : State.EXPIRED;
        return new ExportView(job.id(), state, job.createdAt(), job.expiresAt(), job.failureCode(), state == State.READY ? "/api/v2/exports/" + job.id() + "/download" : null);
    }
    public record Download(InputStreamResource resource, long bytes) {}
    public Download download(UUID owner, UUID id) {
        var job = repository.owned(owner, id);
        if (!job.expiresAt().isAfter(clock.instant())) throw new ApiException(HttpStatus.GONE, "This export expired. Create a new export.");
        if (job.state() != State.READY || job.objectKey() == null || job.byteCount() == null) throw new ApiException(HttpStatus.CONFLICT, "This export is not ready to download.");
        try { return new Download(new InputStreamResource(objects.open(job.objectKey())), job.byteCount()); }
        catch (IOException failure) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Export storage is temporarily unavailable. Try again."); }
    }
}
