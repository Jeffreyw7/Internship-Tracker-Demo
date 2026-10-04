// Defines the supported application pipeline statuses and their user-facing JSON labels.
package com.campus.tracker.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum ApplicationStatus {
    SAVED("Saved"), APPLIED("Applied"), INTERVIEWING("Interviewing"), OFFER("Offer"), REJECTED("Rejected"), WITHDRAWN("Withdrawn");
    // Keep readable API labels separate from the enum names stored by JPA.
    private final String label;
    ApplicationStatus(String label) { this.label = label; }
    // Serialize the readable label in API responses.
    @JsonValue public String label() { return label; }
    // Parse labels case-insensitively and reject unsupported values.
    @JsonCreator public static ApplicationStatus parse(String input) {
        return Arrays.stream(values()).filter(s -> s.label.equalsIgnoreCase(input)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid application status"));
    }
}
