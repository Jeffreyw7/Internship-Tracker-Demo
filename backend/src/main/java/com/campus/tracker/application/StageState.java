package com.campus.tracker.application;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum StageState {
    PLANNED("Planned"), SCHEDULED("Scheduled"), COMPLETED("Completed");
    private final String label;
    StageState(String label) { this.label = label; }
    @JsonValue public String label() { return label; }
    @JsonCreator public static StageState parse(String input) {
        return Arrays.stream(values()).filter(s -> s.label.equalsIgnoreCase(input)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid stage state"));
    }
}
