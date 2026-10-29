package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;
import static com.campus.tracker.application.ApplicationDtos.*;

/** The same complete-record validation applies to HTTP saves and internally assembled import records. */
@Component
public class ApplicationRules {
    private final Validator validator;

    public ApplicationRules(Validator validator) { this.validator = validator; }

    public ApplicationRequest normalizeAndValidate(ApplicationRequest input) {
        beanValidate(input);
        var normalized = new ApplicationRequest(input.company().trim(), input.role().trim(), input.location().trim(),
                input.status(), input.appliedDate(), input.deadline(), input.followUpDate(), input.notes(),
                input.stages().stream().map(s -> new StageRequest(s.id(), s.name().trim(), s.date(), s.state())).toList());
        beanValidate(normalized);
        if (normalized.status() != ApplicationStatus.SAVED && normalized.appliedDate() == null)
            throw invalid("appliedDate", "Add the date you applied.");
        // Retain the ordinary save's server-local calendar rule; date-only inputs are never shifted through UTC.
        if (normalized.appliedDate() != null && normalized.appliedDate().isAfter(LocalDate.now()))
            throw invalid("appliedDate", "The application date cannot be in the future.");
        if (normalized.stages().stream().anyMatch(s -> s.state() == StageState.SCHEDULED && s.date() == null))
            throw invalid("stages", "Choose a date for each scheduled interview.");
        Set<UUID> stageIds = new HashSet<>();
        for (StageRequest stage : normalized.stages()) {
            if (stage.id() != null && !stageIds.add(stage.id())) throw invalid("stages", "Duplicate interview stage ID.");
        }
        return normalized;
    }

    public void validate(ApplicationRequest input) { normalizeAndValidate(input); }

    private void beanValidate(ApplicationRequest input) {
        if (input == null) throw invalid("record", "Application details are required.");
        var errors = validator.validate(input);
        if (!errors.isEmpty()) {
            var first = errors.stream().min(Comparator.comparing(e -> e.getPropertyPath().toString())).orElseThrow();
            // Constraint messages describe the rule, never the rejected CSV value.
            String path = first.getPropertyPath().toString();
            String field = path.startsWith("stages") ? "stages" : path;
            throw invalid(field, path + ": " + first.getMessage());
        }
    }

    public static ApplicationRequest fromView(ApplicationView view) {
        return new ApplicationRequest(view.company(), view.role(), view.location(), view.status(),
                date(view.appliedDate()), date(view.deadline()), date(view.followUpDate()), view.notes(),
                view.stages().stream().map(s -> new StageRequest(s.id(), s.name(), date(s.date()), s.state())).toList());
    }

    private static LocalDate date(String date) { return date == null || date.isEmpty() ? null : LocalDate.parse(date); }
    public static final class ValidationFailure extends ApiException {
        private final String field;
        private ValidationFailure(String field, String message) {
            super(HttpStatus.BAD_REQUEST, "APPLICATION_VALIDATION_FAILED", message);
            this.field = field;
        }
        public String field() { return field; }
    }
    private static ValidationFailure invalid(String field, String message) { return new ValidationFailure(field, message); }
}
