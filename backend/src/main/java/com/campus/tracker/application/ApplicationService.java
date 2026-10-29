// Implements application business rules, ownership checks, and transactional persistence.
package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.campus.tracker.application.ApplicationDtos.*;

@Service
@Transactional
public class ApplicationService {
    private final ApplicationRepository repository;
    private final ApplicationRules rules;
    // Receive the repository used by all reads and writes.
    public ApplicationService(ApplicationRepository repository, ApplicationRules rules) { this.repository = repository; this.rules = rules; }

    // Build an owner-scoped search and return one page of response objects.
    @Transactional(readOnly = true)
    public ApplicationPage list(UUID ownerId, String query, String status, int page, int size, String sort) {
        ApplicationStatus selected = status == null || status.isBlank() ? null : ApplicationStatus.parse(status);
        Specification<InternshipApplication> spec = (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerId"), ownerId));
            predicates.add(cb.isFalse(root.get("deleted")));
            if (selected != null) predicates.add(cb.equal(root.get("status"), selected));
            if (query != null && !query.isBlank()) {
                // Escape LIKE wildcards so a search term is treated as literal user text.
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("company")), pattern, '\\'),
                        cb.like(cb.lower(root.get("role")), pattern, '\\'),
                        cb.like(cb.lower(root.get("location")), pattern, '\\'),
                        cb.like(cb.lower(root.get("notes")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        // Allow only known sort fields rather than accepting arbitrary database column names.
        Sort order = switch (sort) {
            case "company" -> Sort.by("company").ascending();
            case "deadline" -> Sort.by("deadline").ascending();
            case "applied" -> Sort.by("appliedDate").descending();
            case "updated" -> Sort.by("updatedAt").descending();
            case "created" -> Sort.by("createdAt").ascending();
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown sort order.");
        };
        var results = repository.findAll(spec, PageRequest.of(page, size, order.and(Sort.by("id"))));
        return new ApplicationPage(results.stream().map(ApplicationView::from).toList(),
                results.getTotalElements(), results.getTotalPages(), page, size);
    }

    // Load a visible record using the same ownership rule as mutations.
    @Transactional(readOnly = true)
    public ApplicationView get(UUID ownerId, UUID id) { return ApplicationView.from(owned(ownerId, id, false)); }

    // The outer import transaction owns these sorted locks through receipt persistence and commit.
    public Map<UUID, ApplicationView> lockForImport(UUID ownerId, Collection<UUID> ids) {
        Map<UUID, ApplicationView> result = new LinkedHashMap<>();
        ids.stream().distinct().sorted().forEach(id -> repository.lockOwnedActive(id, ownerId)
                .ifPresent(a -> result.put(id, ApplicationView.from(a))));
        return result;
    }

    @Transactional(readOnly = true)
    public Map<UUID, ApplicationView> findForImport(UUID ownerId, Collection<UUID> ids) {
        if (ids.size() > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "Too many import targets.");
        Map<UUID, ApplicationView> result = new LinkedHashMap<>();
        if (!ids.isEmpty()) repository.findImportTargets(ownerId, ids).forEach(a -> result.put(a.id, ApplicationView.from(a)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> findImportCandidates(UUID ownerId, String company, String role) {
        if (company == null || role == null || company.trim().isEmpty() || role.trim().isEmpty()) return List.of();
        return repository.findImportCandidates(ownerId, company.trim().toLowerCase(Locale.ROOT),
                        role.trim().toLowerCase(Locale.ROOT), PageRequest.of(0, 6)).stream().map(ApplicationView::from).toList();
    }

    // Create an ordinary user record; sample seeding uses the internal overload.
    public ApplicationView create(UUID ownerId, ApplicationRequest input) { return create(ownerId, input, false); }

    // Apply validation before storing a new application and its stages.
    ApplicationView create(UUID ownerId, ApplicationRequest input, boolean sample) {
        InternshipApplication a = new InternshipApplication(ownerId);
        a.sample = sample;
        apply(a, input);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

    // Update the owned record only if its version still matches the editor.
    public ApplicationView update(UUID ownerId, UUID id, String ifMatch, ApplicationRequest input) {
        InternshipApplication a = owned(ownerId, id, false);
        verifyVersion(a, ifMatch);
        apply(a, input);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

    // Retain the row for undo while hiding it from normal reads.
    public ApplicationView delete(UUID ownerId, UUID id, String ifMatch) {
        InternshipApplication a = owned(ownerId, id, false);
        verifyVersion(a, ifMatch);
        a.deleted = true; a.updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

    // Make a previously deleted record visible again without losing its details.
    public ApplicationView restore(UUID ownerId, UUID id, String ifMatch) {
        InternshipApplication a = owned(ownerId, id, true);
        verifyVersion(a, ifMatch);
        a.deleted = false; a.updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

    private InternshipApplication owned(UUID ownerId, UUID id, boolean deleted) {
        // Scope every read and mutation to the authenticated owner; never accept an owner ID from JSON.
        return repository.findByIdAndOwnerIdAndDeleted(id, ownerId, deleted)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Application not found."));
    }

    // Accept numeric or quoted ETags, then distinguish malformed headers from stale versions.
    private void verifyVersion(InternshipApplication a, String header) {
        if (header == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required for changes.");
        String value = header.trim();
        if (value.matches("\"[0-9]+\"")) value = value.substring(1, value.length() - 1);
        if (!value.matches("[0-9]+")) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid If-Match version.");
        long expected;
        try { expected = Long.parseLong(value); }
        catch (NumberFormatException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid If-Match version."); }
        if (a.version != expected) throw new ApiException(HttpStatus.CONFLICT, "This application changed elsewhere. Refresh before editing again.");
    }

    // Enforce date and interview rules before copying editable fields into the entity.
    private void apply(InternshipApplication a, ApplicationRequest input) {
        input = rules.normalizeAndValidate(input);
        a.company = input.company().trim(); a.role = input.role().trim(); a.location = input.location().trim();
        a.status = input.status(); a.appliedDate = input.appliedDate(); a.deadline = input.deadline();
        a.followUpDate = input.followUpDate(); a.notes = input.notes(); a.updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // Reuse this application\'s existing stages and assign new IDs to newly added ones.
        Map<UUID, InterviewStage> existing = new HashMap<>();
        a.stages.forEach(s -> existing.put(s.id, s));
        List<InterviewStage> next = new ArrayList<>();
        for (int i = 0; i < input.stages().size(); i++) {
            StageRequest inputStage = input.stages().get(i);
            // A supplied UUID can only reuse a stage already owned by this application.
            InterviewStage stage = existing.get(inputStage.id());
            if (stage == null) stage = new InterviewStage(a);
            stage.name = inputStage.name().trim(); stage.date = inputStage.date();
            stage.state = inputStage.state(); stage.position = i; next.add(stage);
        }
        // Remove omitted stages and preserve the submitted display order; JPA deletes orphaned rows.
        a.stages.removeIf(s -> !next.contains(s));
        next.forEach(s -> { if (!a.stages.contains(s)) a.stages.add(s); });
        a.stages.sort(Comparator.comparingInt(s -> s.position));
    }
}
