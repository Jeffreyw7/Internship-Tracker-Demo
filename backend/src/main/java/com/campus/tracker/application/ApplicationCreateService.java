package com.campus.tracker.application;

import com.campus.tracker.auth.UserRepository;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import static com.campus.tracker.application.ApplicationDtos.*;

/** The owner lock serializes an intent's first create, retries, and cancellation across server instances. */
@Service
@Transactional(timeout = 30)
public class ApplicationCreateService {
    private final ApplicationService applications;
    private final ApplicationCreateRepository receipts;
    private final UserRepository users;
    private final ApplicationRules rules;
    private final ObjectMapper json;

    public ApplicationCreateService(ApplicationService applications, ApplicationCreateRepository receipts,
                                    UserRepository users, ApplicationRules rules, ObjectMapper json) {
        this.applications = applications; this.receipts = receipts; this.users = users; this.rules = rules; this.json = json;
    }

    public record Outcome(String status, ApplicationView application) {}

    public ApplicationView create(UUID owner, String header, ApplicationRequest input) {
        // Existing API clients may still create without a durable retry key.
        if (header == null) return applications.create(owner, input);
        UUID key = key(header);
        ApplicationRequest normalized = rules.normalizeAndValidate(input);
        String fingerprint = fingerprint(normalized);
        lockOwner(owner);
        var receipt = receipts.find(owner, key);
        if (receipt.isPresent()) {
            var saved = receipt.get();
            if (saved.applicationId() == null)
                throw error(HttpStatus.CONFLICT, "CREATE_CANCELLED", "This save was cancelled. Start a new application to save again.");
            if (!saved.fingerprint().equals(fingerprint))
                throw error(HttpStatus.CONFLICT, "CREATE_KEY_REUSED", "This save key belongs to different application details.");
            return outcome(owner, saved).application();
        }
        ApplicationView created = applications.create(owner, normalized);
        // A receipt failure rolls back the application and its stages in this same transaction.
        receipts.insert(owner, key, fingerprint, created.id());
        return created;
    }

    public Outcome get(UUID owner, String value) {
        UUID key = key(value);
        lockOwner(owner);
        return outcome(owner, receipts.find(owner, key).orElseThrow(() ->
                error(HttpStatus.NOT_FOUND, "CREATE_NOT_FOUND", "No completed or cancelled save was found for this key.")));
    }

    public Outcome cancel(UUID owner, String value) {
        UUID key = key(value);
        lockOwner(owner);
        var receipt = receipts.find(owner, key);
        if (receipt.isPresent()) return outcome(owner, receipt.get());
        // Retain a tombstone even if the original POST has not reached the server yet.
        receipts.insert(owner, key, null, null);
        return new Outcome("CANCELLED", null);
    }

    private Outcome outcome(UUID owner, ApplicationCreateRepository.Receipt receipt) {
        if (receipt.applicationId() == null) return new Outcome("CANCELLED", null);
        ApplicationView current = receipts.application(owner, receipt.applicationId()).orElseThrow(() ->
                new IllegalStateException("A completed application create receipt has no owned application."));
        return new Outcome("COMPLETED", current);
    }

    private void lockOwner(UUID owner) {
        users.findByIdForUpdate(owner).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found."));
    }

    private UUID key(String value) {
        // UUID.fromString also accepts shortened groups; require a complete UUID representation.
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_CREATE_KEY", "Idempotency-Key must be a UUID.");
        return UUID.fromString(value);
    }

    private String fingerprint(ApplicationRequest input) {
        // A new application always generates stage IDs, so client-supplied IDs do not alter create intent.
        var canonical = new ApplicationRequest(input.company(), input.role(), input.location(), input.status(),
                input.appliedDate(), input.deadline(), input.followUpDate(), input.notes(), input.stages().stream()
                .map(stage -> new StageRequest(null, stage.name(), stage.date(), stage.state())).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint the application create request.", e);
        }
    }

    private static ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}
