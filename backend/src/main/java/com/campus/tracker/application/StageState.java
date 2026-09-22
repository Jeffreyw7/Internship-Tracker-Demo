// Defines the supported interview scheduling states and their user-facing JSON labels.
package com.campus.tracker.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum StageState {
    PLANNED("Planned"), SCHEDULED("Scheduled"), COMPLETED("Completed");
    // Keep readable API labels separate from the enum names stored by JPA.
    private final String label;
    StageState(String label) { this.label = label; }
    // Serialize the readable label in API responses.
    @JsonValue public String label() { return label; }
    // Parse labels case-insensitively and reject unsupported values.
    @JsonCreator public static StageState parse(String input) {
        return Arrays.stream(values()).filter(s -> s.label.equalsIgnoreCase(input)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid stage state"));
    }
}
