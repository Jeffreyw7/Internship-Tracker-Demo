package com.campus.tracker.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum ApplicationStatus {
    SAVED("Saved"), APPLIED("Applied"), INTERVIEWING("Interviewing"), OFFER("Offer"), REJECTED("Rejected"), WITHDRAWN("Withdrawn");
    private final String label;
    ApplicationStatus(String label) { this.label = label; }
    @JsonValue public String label() { return label; }
    @JsonCreator public static ApplicationStatus parse(String input) {
        return Arrays.stream(values()).filter(s -> s.label.equalsIgnoreCase(input)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid application status"));
    }
}
