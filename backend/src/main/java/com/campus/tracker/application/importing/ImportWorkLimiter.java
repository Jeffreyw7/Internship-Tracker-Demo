package com.campus.tracker.application.importing;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Process permits bound active preview upload and construction; persisted quotas are separate. */
@Component
public class ImportWorkLimiter {
    private final Set<UUID> owners = new HashSet<>();
    private final ImportInputLimits limits;
    public ImportWorkLimiter() { this(new ImportInputLimits()); }
    @Autowired public ImportWorkLimiter(ImportInputLimits limits) { this.limits = limits; }
    public synchronized void acquire(UUID owner) {
        if (owners.size() >= limits.processPreviewCount() || !owners.add(owner)) throw ImportUploadPolicy.error(HttpStatus.TOO_MANY_REQUESTS,
                "IMPORT_BUSY", "Import preview capacity is busy. Try again shortly.");
    }
    public synchronized void release(UUID owner) { owners.remove(owner); }
}
