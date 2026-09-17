package com.campus.tracker.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ApplicationDtos {
    private ApplicationDtos() {}
    public record StageRequest(UUID id, @NotBlank @Size(max = 120) String name,
                               LocalDate date, @NotNull StageState state) {}
    public record ApplicationRequest(
            @NotBlank @Size(max = 100) String company,
            @NotBlank @Size(max = 180) String role,
            @NotNull @Size(max = 120) String location,
            @NotNull ApplicationStatus status,
            @PastOrPresent LocalDate appliedDate,
            LocalDate deadline, LocalDate followUpDate,
            @NotNull @Size(max = 10000) String notes,
            @NotNull @Size(max = 30) List<@NotNull @Valid StageRequest> stages) {}
    public record StageView(UUID id, String name, String date, StageState state) {}
    public record ApplicationView(UUID id, String company, String role, String location, ApplicationStatus status,
                                  String appliedDate, String deadline, String followUpDate, String notes,
                                  List<StageView> stages, Instant createdAt, Instant updatedAt, long version,
                                  boolean sample, boolean deleted) {
        static ApplicationView from(InternshipApplication a) {
            return new ApplicationView(a.id, a.company, a.role, a.location, a.status, date(a.appliedDate),
                    date(a.deadline), date(a.followUpDate), a.notes, a.stages.stream()
                    .map(s -> new StageView(s.id, s.name, date(s.date), s.state)).toList(),
                    a.createdAt, a.updatedAt, a.version, a.sample, a.deleted);
        }
    }
    public record ApplicationPage(List<ApplicationView> content, long totalElements, int totalPages, int page, int size) {}
    private static String date(LocalDate d) { return d == null ? "" : d.toString(); }
}
