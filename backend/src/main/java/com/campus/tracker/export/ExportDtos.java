package com.campus.tracker.export;

import java.time.Instant;
import java.util.UUID;

public final class ExportDtos {
    private ExportDtos() {}
    public enum State { PENDING, RUNNING, READY, FAILED, CANCELLED, EXPIRED }
    public record ExportRequest() {}
    public record ExportView(UUID id, State state, Instant createdAt, Instant expiresAt, String failureCode, String downloadPath) {}
}
