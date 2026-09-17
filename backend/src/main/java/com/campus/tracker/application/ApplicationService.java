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
import java.time.LocalDate;
import java.util.*;
import static com.campus.tracker.application.ApplicationDtos.*;

@Service
@Transactional
public class ApplicationService {
    private final ApplicationRepository repository;
    public ApplicationService(ApplicationRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public ApplicationPage list(UUID ownerId, String query, String status, int page, int size, String sort) {
        ApplicationStatus selected = status == null || status.isBlank() ? null : ApplicationStatus.parse(status);
        Specification<InternshipApplication> spec = (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerId"), ownerId));
            predicates.add(cb.isFalse(root.get("deleted")));
            if (selected != null) predicates.add(cb.equal(root.get("status"), selected));
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("company")), pattern, '\\'),
                        cb.like(cb.lower(root.get("role")), pattern, '\\'),
                        cb.like(cb.lower(root.get("location")), pattern, '\\'),
                        cb.like(cb.lower(root.get("notes")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort order = switch (sort) {
            case "company" -> Sort.by("company").ascending();
            case "deadline" -> Sort.by("deadline").ascending();
            case "applied" -> Sort.by("appliedDate").descending();
            case "updated" -> Sort.by("updatedAt").descending();
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown sort order.");
        };
        var results = repository.findAll(spec, PageRequest.of(page, size, order.and(Sort.by("id"))));
        return new ApplicationPage(results.stream().map(ApplicationView::from).toList(),
                results.getTotalElements(), results.getTotalPages(), page, size);
    }

    @Transactional(readOnly = true)
    public ApplicationView get(UUID ownerId, UUID id) { return ApplicationView.from(owned(ownerId, id, false)); }

    public ApplicationView create(UUID ownerId, ApplicationRequest input) { return create(ownerId, input, false); }

    ApplicationView create(UUID ownerId, ApplicationRequest input, boolean sample) {
        InternshipApplication a = new InternshipApplication(ownerId);
        a.sample = sample;
        apply(a, input);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

    public ApplicationView update(UUID ownerId, UUID id, String ifMatch, ApplicationRequest input) {
        InternshipApplication a = owned(ownerId, id, false);
        verifyVersion(a, ifMatch);
        apply(a, input);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

    public ApplicationView delete(UUID ownerId, UUID id, String ifMatch) {
        InternshipApplication a = owned(ownerId, id, false);
        verifyVersion(a, ifMatch);
        a.deleted = true; a.updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return ApplicationView.from(repository.saveAndFlush(a));
    }

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

    private void apply(InternshipApplication a, ApplicationRequest input) {
        if (input.status() != ApplicationStatus.SAVED && input.appliedDate() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add the date you applied.");
        if (input.appliedDate() != null && input.appliedDate().isAfter(LocalDate.now()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "The application date cannot be in the future.");
        if (input.stages().stream().anyMatch(s -> s.state() == StageState.SCHEDULED && s.date() == null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a date for each scheduled interview.");
        Set<UUID> stageIds = new HashSet<>();
        for (StageRequest s : input.stages()) {
            if (s.id() != null && !stageIds.add(s.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate interview stage ID.");
        }
        a.company = input.company().trim(); a.role = input.role().trim(); a.location = input.location().trim();
        a.status = input.status(); a.appliedDate = input.appliedDate(); a.deadline = input.deadline();
        a.followUpDate = input.followUpDate(); a.notes = input.notes(); a.updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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
        a.stages.removeIf(s -> !next.contains(s));
        next.forEach(s -> { if (!a.stages.contains(s)) a.stages.add(s); });
        a.stages.sort(Comparator.comparingInt(s -> s.position));
    }
}
